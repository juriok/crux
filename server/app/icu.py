"""Minimal intervals.icu client (stdlib only).

Auth is HTTP Basic with the literal username "API_KEY" and the athlete's API key
as the password. Athlete id "0" means "whoever owns the key".
"""
import base64
import json
import re
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://intervals.icu/api/v1"
USER_AGENT = "Crux/1.0 (personal training app)"

# Session kinds in a Crux plan -> intervals.icu activity types. Only Run/Walk/Hike
# reach the Huawei watch; the rest just make the intervals.icu calendar complete.
EVENT_TYPES = {
    "run": "Run",
    "walk": "Walk",
    "hike": "Hike",
    "strength": "WeightTraining",
    "climb": "RockClimbing",
    "mobility": "Yoga",
}

EXTERNAL_PREFIX = "crux-"

WELLNESS_FIELDS = [
    "id", "sleepSecs", "sleepScore", "sleepQuality", "avgSleepingHR", "restingHR",
    "hrv", "hrvSDNN", "readiness", "weight", "vo2max", "steps", "spO2", "stress",
    "soreness", "fatigue", "mood", "motivation", "injury", "comments", "ctl", "atl",
]

ACTIVITY_FIELDS = [
    "id", "start_date_local", "type", "name", "description", "moving_time",
    "elapsed_time", "distance", "total_elevation_gain", "average_speed",
    "average_heartrate", "max_heartrate", "average_cadence", "icu_training_load",
    "icu_intensity", "trimp", "icu_hr_zone_times", "icu_rpe", "feel", "session_rpe",
    "calories", "lthr", "external_id", "source",
]


class IcuError(Exception):
    pass


class Icu:
    def __init__(self, api_key: str, athlete_id: str = "0", timeout: float = 20):
        self.athlete = athlete_id or "0"
        self.timeout = timeout
        cred = base64.b64encode(f"API_KEY:{api_key}".encode()).decode()
        self._auth = f"Basic {cred}"

    def _req(self, method, path, params=None, body=None):
        url = BASE + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", self._auth)
        req.add_header("Accept", "application/json")
        # Cloudflare in front of intervals.icu blocks urllib's default
        # "Python-urllib/x.y" agent outright (error 1010).
        req.add_header("User-Agent", USER_AGENT)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                raw = r.read()
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            raise IcuError(f"intervals.icu {method} {path} -> {e.code}: {detail}") from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise IcuError(f"intervals.icu unreachable: {e}") from e
        return json.loads(raw) if raw else None

    def athlete_info(self):
        return self._req("GET", f"/athlete/{self.athlete}")

    def wellness(self, oldest: str, newest: str):
        return self._req("GET", f"/athlete/{self.athlete}/wellness", {
            "oldest": oldest, "newest": newest, "fields": ",".join(WELLNESS_FIELDS),
        })

    def activities(self, oldest: str, newest: str):
        return self._req("GET", f"/athlete/{self.athlete}/activities", {
            "oldest": oldest, "newest": newest, "fields": ",".join(ACTIVITY_FIELDS),
        })

    def events(self, oldest: str, newest: str):
        return self._req("GET", f"/athlete/{self.athlete}/events", {
            "oldest": oldest, "newest": newest, "category": "WORKOUT",
        })

    def delete_events(self, ids):
        if not ids:
            return 0
        res = self._req("PUT", f"/athlete/{self.athlete}/events/bulk-delete",
                        body=[{"id": i} for i in ids])
        return (res or {}).get("eventsDeleted", len(ids))

    def sport_settings(self, sport="Run"):
        return self._req("GET", f"/athlete/{self.athlete}/sport-settings/{sport}")

    def update_event(self, event_id, event):
        return self._req("PUT", f"/athlete/{self.athlete}/events/{event_id}", body=event)

    def create_events(self, events):
        if not events:
            return []
        return self._req("POST", f"/athlete/{self.athlete}/events/bulk",
                         {"upsertOnUid": "false", "updatePlanApplied": "false"}, events)


def _fmt_load(load, load_type):
    if load is None or load_type == "bodyweight":
        return ""
    if load_type == "added":
        return " BW" if load == 0 else f" @ BW{load:+g} kg"
    if load_type == "assisted":
        return f" @ -{abs(load):g} kg assist"
    return f" @ {load:g} kg"


def _strength_text(session, exercises):
    """Plain-text summary for the intervals.icu calendar. Lines deliberately do
    not start with '-' so intervals.icu does not parse them as workout steps."""
    lines = []
    for b in session.get("blocks", []):
        ex = exercises.get(b.get("exercise"), {})
        name = ex.get("name", b.get("exercise", "?"))
        sets = []
        for s in b.get("sets", []):
            sets.extend([s] * int(s.get("repeat", 1) or 1))
        if not sets:
            lines.append(name)
            continue
        first = sets[0]
        if first.get("seconds"):
            work = "max hold" if first.get("amrap") else f"{first['seconds']}s"
        elif first.get("reps"):
            work = f"{first['reps']}{'+' if first.get('amrap') else ''}"
        else:
            work = "max" if first.get("amrap") else "as noted"
        loads = {s.get("load") for s in sets}
        load = _fmt_load(first.get("load"), ex.get("load_type", "total")) if len(loads) == 1 else " @ varied load"
        lines.append(f"{name}: {len(sets)}x{work}{load}")
    return "\n".join(lines)


_REPEAT_HEADER = re.compile(r"^(.*?)(\d+)\s*x\s*$", re.IGNORECASE)


def unroll_repeats(text: str) -> str:
    """Writes "Main set 5x" blocks out step by step. Huawei silently dropped a
    repeat block that intervals.icu had parsed fine; plain steps survive."""
    lines = text.strip().splitlines()
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        m = _REPEAT_HEADER.match(line.strip())
        if m and not line.lstrip().startswith("-"):
            j = i + 1
            block = []
            while j < len(lines) and lines[j].strip().startswith("-"):
                block.append(lines[j])
                j += 1
            if block:
                if m.group(1).strip():
                    out.append(m.group(1).strip())
                for _ in range(int(m.group(2))):
                    out.extend(block)
                i = j
                continue
        out.append(line)
        i += 1
    return "\n".join(out)


def workout_targets(text: str) -> set:
    """Target types used by the steps of an intervals.icu workout: {"HR"}, {"PACE"}, or both.

    Huawei only receives the steps of one type: in a run mixing HR and pace steps,
    the pace steps (strides, 800 m reps) never reached the watch."""
    kinds = set()
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("-"):
            continue
        if "Pace" in line:
            kinds.add("PACE")
        elif "HR" in line:  # "Z2 HR" and "% LTHR"
            kinds.add("HR")
    return kinds


def plan_events(plan, today: str):
    """Calendar events for every plan session dated today or later."""
    exercises = plan.get("exercises", {})
    out = []
    for s in plan.get("sessions", []):
        if s.get("date", "") < today:
            continue
        typ = EVENT_TYPES.get(s.get("kind"))
        if not typ:
            continue
        parts = []
        if s.get("summary"):
            parts.append(s["summary"])
        run = s.get("run") or {}
        if run.get("workout"):
            parts.append(unroll_repeats(run["workout"]))
        elif s.get("blocks"):
            parts.append(_strength_text(s, exercises))
        ev = {
            "category": "WORKOUT",
            "type": typ,
            "start_date_local": f"{s['date']}T00:00:00",
            "name": s.get("title") or typ,
            "description": "\n\n".join(parts),
            "external_id": EXTERNAL_PREFIX + s["id"],
        }
        if s.get("duration_min"):
            ev["moving_time"] = int(s["duration_min"]) * 60
        # No "distance": intervals.icu overwrites it with its own estimate from the
        # steps, which made every push look like a change.
        kinds = workout_targets(run.get("workout", ""))
        if len(kinds) == 1:
            ev["target"] = kinds.pop()  # what the device export follows; AUTO guessed wrong
        out.append(ev)
    return out

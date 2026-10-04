"""Crux API: training plan and workout log store for the Crux Android app.

Claude Code (through coach/coach.py) writes the plan, the phone reads it and
writes workout logs back. intervals.icu is proxied so its API key lives only
on this server.
"""
import datetime as dt
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, FastAPI, HTTPException, Request, Response

from . import icu

DATA_DIR = os.environ.get("DATA_DIR", "/data")
TOKEN = os.environ.get("CRUX_TOKEN", "")
TZ = ZoneInfo(os.environ.get("TZ", "Europe/Ljubljana"))
# Tailscale Funnel mounts the service at /crux. Whether it strips that prefix
# or not, both forms route to the same handlers.
PREFIX = "/crux"
STARTED_AT = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")

if len(TOKEN) < 32 or TOKEN.startswith("CHANGEME"):
    raise SystemExit("CRUX_TOKEN is missing or too short - set it in .env")

# ---------------------------------------------------------------- storage

os.makedirs(DATA_DIR, exist_ok=True)
_lock = threading.Lock()
_db = sqlite3.connect(os.path.join(DATA_DIR, "crux.db"), check_same_thread=False)
_db.row_factory = sqlite3.Row
_db.executescript("""
CREATE TABLE IF NOT EXISTS plans (
    version    INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TEXT NOT NULL,
    body       TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS logs (
    id         TEXT PRIMARY KEY,
    date       TEXT NOT NULL,
    session_id TEXT,
    status     TEXT,
    updated_at TEXT NOT NULL,
    body       TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS logs_date ON logs(date);
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pair_codes (
    code       TEXT PRIMARY KEY,
    expires_at REAL NOT NULL
);
""")
_db.commit()


def db(sql, args=()):
    with _lock:
        rows = _db.execute(sql, args).fetchall()
        _db.commit()
        return rows


def setting(key, default=None):
    rows = db("SELECT value FROM settings WHERE key = ?", (key,))
    return rows[0]["value"] if rows else default


def now_iso():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def today_local():
    return dt.datetime.now(TZ).date()


def latest_plan():
    rows = db("SELECT version, created_at, body FROM plans ORDER BY version DESC LIMIT 1")
    if not rows:
        return None
    plan = json.loads(rows[0]["body"])
    plan["version"] = rows[0]["version"]
    plan["published_at"] = rows[0]["created_at"]
    return plan


# ---------------------------------------------------------------- validation

KINDS = {"strength", "run", "climb", "mobility", "walk", "hike", "rest", "other"}
LOAD_TYPES = {"total", "added", "assisted", "bodyweight"}
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,48}$")


def _is_date(s):
    try:
        dt.date.fromisoformat(s)
        return isinstance(s, str) and len(s) == 10
    except (TypeError, ValueError):
        return False


def _num(v, lo, hi):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and lo <= v <= hi


def validate_plan(p):
    errors, warnings = [], []
    if not isinstance(p, dict):
        return ["plan must be a JSON object"], []
    if not isinstance(p.get("title"), str) or not p["title"].strip():
        errors.append("title: required string")
    for k in ("start_date", "end_date"):
        if k in p and not _is_date(p[k]):
            errors.append(f"{k}: must be YYYY-MM-DD")

    exercises = p.get("exercises", {})
    if not isinstance(exercises, dict):
        errors.append("exercises: must be an object keyed by exercise id")
        exercises = {}
    for eid, ex in exercises.items():
        where = f"exercises.{eid}"
        if not ID_RE.match(eid):
            errors.append(f"{where}: id must match {ID_RE.pattern}")
        if not isinstance(ex, dict):
            errors.append(f"{where}: must be an object")
            continue
        if not isinstance(ex.get("name"), str) or not ex["name"].strip():
            errors.append(f"{where}.name: required")
        if ex.get("load_type", "total") not in LOAD_TYPES:
            errors.append(f"{where}.load_type: one of {sorted(LOAD_TYPES)}")
        if "cues" in ex and not (isinstance(ex["cues"], list) and all(isinstance(c, str) for c in ex["cues"])):
            errors.append(f"{where}.cues: list of strings")

    sessions = p.get("sessions")
    if not isinstance(sessions, list):
        errors.append("sessions: required list")
        sessions = []
    seen, used = set(), set()
    for i, s in enumerate(sessions):
        where = f"sessions[{i}]"
        if not isinstance(s, dict):
            errors.append(f"{where}: must be an object")
            continue
        sid = s.get("id")
        if not isinstance(sid, str) or not ID_RE.match(sid):
            errors.append(f"{where}.id: required, must match {ID_RE.pattern}")
        elif sid in seen:
            errors.append(f"{where}.id: duplicate '{sid}'")
        else:
            seen.add(sid)
            where = f"session '{sid}'"
        if not _is_date(s.get("date")):
            errors.append(f"{where}.date: must be YYYY-MM-DD")
        kind = s.get("kind", "strength")
        if kind not in KINDS:
            errors.append(f"{where}.kind: one of {sorted(KINDS)}")
        if not isinstance(s.get("title", ""), str) or not s.get("title"):
            errors.append(f"{where}.title: required")
        if "duration_min" in s and not _num(s["duration_min"], 1, 600):
            errors.append(f"{where}.duration_min: 1..600")
        for k in ("warmup", "cooldown"):
            if k in s and not (isinstance(s[k], list) and all(isinstance(x, str) for x in s[k])):
                errors.append(f"{where}.{k}: list of strings")

        blocks = s.get("blocks", [])
        if not isinstance(blocks, list):
            errors.append(f"{where}.blocks: must be a list")
            blocks = []
        if kind == "strength" and not blocks:
            errors.append(f"{where}: strength session needs blocks")
        for j, b in enumerate(blocks):
            bw = f"{where}.blocks[{j}]"
            if not isinstance(b, dict):
                errors.append(f"{bw}: must be an object")
                continue
            eid = b.get("exercise")
            if eid not in exercises:
                errors.append(f"{bw}.exercise: '{eid}' not in exercises")
            else:
                used.add(eid)
            sets = b.get("sets")
            if not isinstance(sets, list) or not sets:
                errors.append(f"{bw}.sets: non-empty list required")
                continue
            for k, st in enumerate(sets):
                sw = f"{bw}.sets[{k}]"
                if not isinstance(st, dict):
                    errors.append(f"{sw}: must be an object")
                    continue
                if "reps" in st and not (isinstance(st["reps"], int) and 1 <= st["reps"] <= 100):
                    errors.append(f"{sw}.reps: integer 1..100")
                if "seconds" in st and not (isinstance(st["seconds"], int) and 1 <= st["seconds"] <= 3600):
                    errors.append(f"{sw}.seconds: integer 1..3600")
                if "reps" in st and "seconds" in st:
                    errors.append(f"{sw}: use reps or seconds, not both")
                if "load" in st and not _num(st["load"], -200, 500):
                    errors.append(f"{sw}.load: number in kg")
                if "rest_s" in st and not (isinstance(st["rest_s"], int) and 0 <= st["rest_s"] <= 900):
                    errors.append(f"{sw}.rest_s: integer 0..900")
                if "rpe" in st and not _num(st["rpe"], 1, 10):
                    errors.append(f"{sw}.rpe: 1..10")
                if "repeat" in st and not (isinstance(st["repeat"], int) and 1 <= st["repeat"] <= 20):
                    errors.append(f"{sw}.repeat: integer 1..20")
                if "reps" not in st and "seconds" not in st and not st.get("note"):
                    warnings.append(f"{sw}: no reps or seconds - add a note saying what to do")

        if kind == "run":
            run = s.get("run")
            if not isinstance(run, dict):
                errors.append(f"{where}.run: required for kind=run")
            else:
                if not run.get("workout"):
                    warnings.append(f"{where}.run.workout: empty - nothing structured reaches the watch")
                elif len(icu.workout_targets(run["workout"])) > 1:
                    warnings.append(f"{where}.run.workout: mixes HR and Pace steps - the Huawei watch only "
                                    "gets one kind (the pace steps were dropped). Use one target type per run")
                if "distance_km" in run and not _num(run["distance_km"], 0.1, 200):
                    errors.append(f"{where}.run.distance_km: 0.1..200")

    unused = set(exercises) - used
    if unused:
        warnings.append(f"exercises never used: {sorted(unused)}")
    return errors, warnings


def validate_log(log):
    if not isinstance(log, dict):
        return "log must be an object"
    if not isinstance(log.get("id"), str) or not ID_RE.match(log["id"]):
        return f"id: required, must match {ID_RE.pattern}"
    if not _is_date(log.get("date")):
        return "date: must be YYYY-MM-DD"
    if "sets" in log and not isinstance(log["sets"], list):
        return "sets: must be a list"
    return None


# ---------------------------------------------------------------- intervals.icu

_cache = {}
_CACHE_TTL = 300


def icu_client():
    key = setting("icu_api_key")
    return icu.Icu(key, setting("icu_athlete_id", "0")) if key else None


def icu_cached(kind, days, fresh=False):
    c = icu_client()
    if not c:
        raise HTTPException(409, "intervals.icu is not configured - run: python coach/coach.py icu-key")
    k = (kind, days)
    hit = _cache.get(k)
    if hit and not fresh and time.time() - hit[0] < _CACHE_TTL:
        return hit[1]
    newest = today_local()
    oldest = newest - dt.timedelta(days=days - 1)
    fn = c.wellness if kind == "wellness" else c.activities
    try:
        data = fn(oldest.isoformat(), newest.isoformat())
    except icu.IcuError as e:
        if hit:  # stale beats nothing when the uplink is down
            return hit[1]
        raise HTTPException(502, str(e))
    _cache[k] = (time.time(), data)
    return data


_COMPARED = ("type", "start_date_local", "name", "description", "moving_time", "target")


def push_plan_to_icu(plan, force=False, recreate=False):
    """Mirrors plan sessions from today on into the intervals.icu calendar.

    Events are edited in place, matched on external_id. Deleting and recreating
    them left the old copy on the Huawei watch: intervals.icu does not re-send a
    workout it has already uploaded when a new event replaces it. `recreate` is
    for after a sport-settings change (e.g. threshold pace): intervals.icu only
    rebuilds a workout's watch export when the event is created.
    """
    c = icu_client()
    if not c:
        return {"configured": False}
    today = today_local()
    try:
        existing = {}
        for e in c.events(today.isoformat(), (today + dt.timedelta(days=366)).isoformat()) or []:
            if (e.get("external_id") or "").startswith(icu.EXTERNAL_PREFIX):
                existing.setdefault(e["external_id"], []).append(e)
        recreated = 0
        if recreate:
            recreated = c.delete_events([e["id"] for evs in existing.values() for e in evs])
            existing = {}
        new, doomed, updated, unchanged = [], [], 0, 0
        for ev in icu.plan_events(plan, today.isoformat()):
            matches = existing.pop(ev["external_id"], [])
            if not matches:
                new.append(ev)
                continue
            old = matches[0]
            doomed += matches[1:]
            # Only fields we set: intervals.icu fills in others (e.g. a distance
            # derived from pace steps) that would otherwise always look changed.
            if force or any(old.get(k) != ev[k] for k in _COMPARED if k in ev):
                c.update_event(old["id"], ev)
                updated += 1
            else:
                unchanged += 1
        doomed += [e for evs in existing.values() for e in evs]  # sessions no longer in the plan
        created = c.create_events(new)
        deleted = c.delete_events([e["id"] for e in doomed])
        result = {"configured": True, "updated": updated, "unchanged": unchanged,
                  "created": len(created or []), "deleted": deleted + recreated}
        # Without a Run threshold pace, intervals.icu silently drops every
        # pace-target step from what it sends to the watch.
        if any(e["type"] == "Run" and " Pace" in e["description"] for e in icu.plan_events(plan, today.isoformat())):
            if not (c.sport_settings("Run") or {}).get("threshold_pace"):
                result["warning"] = ("Run threshold pace is not set in intervals.icu, so pace steps "
                                     "never reach the watch. Set it under Settings -> Run.")
    except icu.IcuError as e:
        return {"configured": True, "error": str(e)}
    return result


# ---------------------------------------------------------------- auth + app


def require_token(request: Request):
    got = request.headers.get("authorization", "")
    if not secrets.compare_digest(got.encode(), f"Bearer {TOKEN}".encode()):
        raise HTTPException(401, "bad or missing token")


app = FastAPI(title="Crux API", docs_url=None, redoc_url=None, openapi_url=None)
api = APIRouter(prefix="/api", dependencies=[Depends(require_token)])


@app.get("/api/status")
def status():
    plan = db("SELECT MAX(version) AS v FROM plans")[0]["v"]
    logs = db("SELECT COUNT(*) AS n FROM logs")[0]["n"]
    return {"ok": True, "started_at": STARTED_AT, "plan_version": plan, "logs": logs,
            "icu_configured": setting("icu_api_key") is not None, "today": today_local().isoformat()}


# Pairing: the phone types a 6-digit code instead of a 43-character token.
_pair_attempts = []


@api.post("/pair/new")
def pair_new():
    code = f"{secrets.randbelow(10**6):06d}"
    db("DELETE FROM pair_codes WHERE expires_at < ?", (time.time(),))
    db("INSERT OR REPLACE INTO pair_codes (code, expires_at) VALUES (?, ?)", (code, time.time() + 600))
    return {"code": code, "expires_in": 600}


@app.post("/api/pair")
async def pair(request: Request):
    now = time.time()
    _pair_attempts[:] = [t for t in _pair_attempts if now - t < 60]
    if len(_pair_attempts) >= 10:
        raise HTTPException(429, "too many attempts, wait a minute")
    _pair_attempts.append(now)
    body = await request.json()
    code = str(body.get("code", "")).strip()
    rows = db("SELECT code FROM pair_codes WHERE code = ? AND expires_at >= ?", (code, now))
    if not rows:
        raise HTTPException(403, "wrong or expired code")
    db("DELETE FROM pair_codes WHERE code = ?", (code,))
    return {"token": TOKEN}


@api.get("/plan")
def get_plan(request: Request):
    plan = latest_plan()
    if not plan:
        raise HTTPException(404, "no plan published yet")
    etag = f'"v{plan["version"]}"'
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers={"ETag": etag})
    return Response(json.dumps(plan), media_type="application/json", headers={"ETag": etag})


@api.put("/plan")
async def put_plan(request: Request, dry_run: bool = False, push_icu: bool = True):
    plan = await request.json()
    errors, warnings = validate_plan(plan)
    if errors or dry_run:
        return Response(json.dumps({"ok": not errors, "errors": errors, "warnings": warnings}),
                        status_code=422 if errors else 200, media_type="application/json")
    plan.pop("version", None)
    plan.pop("published_at", None)
    created = now_iso()
    with _lock:
        cur = _db.execute("INSERT INTO plans (created_at, body) VALUES (?, ?)", (created, json.dumps(plan)))
        _db.commit()
        version = cur.lastrowid
    result = {"ok": True, "version": version, "warnings": warnings}
    if push_icu:
        result["icu"] = push_plan_to_icu(plan)
    return result


@api.get("/plans")
def list_plans():
    rows = db("SELECT version, created_at, body FROM plans ORDER BY version DESC")
    return [{"version": r["version"], "created_at": r["created_at"],
             "title": json.loads(r["body"]).get("title", "")} for r in rows]


@api.get("/plans/{version}")
def get_plan_version(version: int):
    rows = db("SELECT version, created_at, body FROM plans WHERE version = ?", (version,))
    if not rows:
        raise HTTPException(404, "no such version")
    plan = json.loads(rows[0]["body"])
    plan["version"], plan["published_at"] = rows[0]["version"], rows[0]["created_at"]
    return plan


@api.post("/icu/push")
def icu_push(force: bool = False, recreate: bool = False):
    plan = latest_plan()
    if not plan:
        raise HTTPException(404, "no plan published yet")
    return push_plan_to_icu(plan, force=force, recreate=recreate)


@api.post("/logs")
async def post_logs(request: Request):
    body = await request.json()
    logs = body if isinstance(body, list) else [body]
    stored = []
    for log in logs:
        err = validate_log(log)
        if err:
            raise HTTPException(422, f"log {log.get('id') if isinstance(log, dict) else '?'}: {err}")
    for log in logs:
        db("""INSERT INTO logs (id, date, session_id, status, updated_at, body) VALUES (?, ?, ?, ?, ?, ?)
              ON CONFLICT(id) DO UPDATE SET date = excluded.date, session_id = excluded.session_id,
              status = excluded.status, updated_at = excluded.updated_at, body = excluded.body""",
           (log["id"], log["date"], log.get("session_id"), log.get("status"), now_iso(), json.dumps(log)))
        stored.append(log["id"])
    return {"stored": stored}


@api.get("/logs")
def get_logs(since: str = "", days: int = 0):
    if days:
        since = (today_local() - dt.timedelta(days=days - 1)).isoformat()
    rows = db("SELECT body FROM logs WHERE date >= ? ORDER BY date, id", (since or "0000",))
    return [json.loads(r["body"]) for r in rows]


@api.delete("/logs/{log_id}")
def delete_log(log_id: str):
    db("DELETE FROM logs WHERE id = ?", (log_id,))
    return {"deleted": log_id}


@api.get("/wellness")
def wellness(days: int = 14, fresh: bool = False):
    return icu_cached("wellness", max(1, min(days, 365)), fresh)


@api.get("/activities")
def activities(days: int = 28, fresh: bool = False):
    return icu_cached("activities", max(1, min(days, 365)), fresh)


@api.get("/context")
def context(days: int = 28):
    """Everything a coach needs in one call."""
    days = max(1, min(days, 365))
    out = {"generated_at": now_iso(), "today": today_local().isoformat(), "days": days,
           "plan": latest_plan(), "logs": get_logs(days=days)}
    for kind in ("activities", "wellness"):
        try:
            out[kind] = icu_cached(kind, days, fresh=True)
        except HTTPException as e:
            out[kind] = None
            out[f"{kind}_error"] = e.detail
    return out


@api.get("/settings/icu")
def get_icu_settings():
    return {"configured": setting("icu_api_key") is not None,
            "athlete_id": setting("icu_athlete_id", "0"),
            "athlete_name": setting("icu_athlete_name")}


@api.post("/settings/icu")
async def set_icu_settings(request: Request):
    body = await request.json()
    key = str(body.get("api_key", "")).strip()
    athlete = str(body.get("athlete_id") or "0").strip()
    if not key:
        raise HTTPException(422, "api_key required")
    try:
        info = icu.Icu(key, athlete).athlete_info() or {}
    except icu.IcuError as e:
        raise HTTPException(400, f"intervals.icu rejected the key: {e}")
    name = info.get("name") or info.get("firstname") or ""
    resolved = str(info.get("id") or athlete)
    for k, v in (("icu_api_key", key), ("icu_athlete_id", resolved), ("icu_athlete_name", name)):
        db("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", (k, v))
    _cache.clear()
    return {"configured": True, "athlete_id": resolved, "athlete_name": name}


app.include_router(api)


class StripPrefix:
    def __init__(self, inner):
        self.inner = inner

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            p = scope["path"]
            if p == PREFIX or p.startswith(PREFIX + "/"):
                scope = dict(scope, path=p[len(PREFIX):] or "/")
        await self.inner(scope, receive, send)


asgi = StripPrefix(app)

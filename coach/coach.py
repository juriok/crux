#!/usr/bin/env python3
"""Coach-side CLI for the Crux server. Stdlib only.

    python coach/coach.py status
    python coach/coach.py context [--days 28] [--json]
    python coach/coach.py plan check FILE
    python coach/coach.py plan push FILE [--no-icu]
    python coach/coach.py plan pull [-o FILE]
    python coach/coach.py plan history
    python coach/coach.py logs [--days 28]
    python coach/coach.py pair            # 6-digit code to pair the phone
    python coach/coach.py icu-key         # store the intervals.icu API key on the server
    python coach/coach.py icu-push        # re-send the current plan to intervals.icu

Server URL and token come from coach/config.json (git-ignored):
    {"url": "http://<server-ip>:8094", "token": "..."}
"""
import argparse
import datetime as dt
import getpass
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(HERE, "config.json")

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def load_config():
    if not os.path.exists(CONFIG):
        sys.exit(f"missing {CONFIG} - create it with url and token")
    with open(CONFIG, encoding="utf-8") as f:
        cfg = json.load(f)
    return cfg["url"].rstrip("/"), cfg["token"]


def call(method, path, body=None, ok=(200,)):
    url, token = load_config()
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            payload = json.loads(raw)
        except ValueError:
            payload = raw.decode(errors="replace")
        if e.code in ok:
            return e.code, payload
        sys.exit(f"HTTP {e.code} {method} {path}: {payload}")
    except urllib.error.URLError as e:
        sys.exit(f"server unreachable ({url}): {e.reason}")


# ---------------------------------------------------------------- formatting

WEEKDAY = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
RUN_TYPES = {"Run", "TrailRun", "VirtualRun"}


def day(d):
    try:
        return f"{d} {WEEKDAY[dt.date.fromisoformat(d[:10]).weekday()]}"
    except ValueError:
        return d


def hms(secs):
    if not secs:
        return "-"
    secs = int(secs)
    h, m, s = secs // 3600, secs % 3600 // 60, secs % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def load_str(load, load_type):
    if load is None or load_type == "bodyweight":
        return ""
    if load_type == "added":
        return "BW" if load == 0 else f"BW{load:+g}"
    if load_type == "assisted":
        return f"-{abs(load):g} assist"
    return f"{load:g}kg"


def fmt_target(sets, load_type):
    """Compress a list of set targets: 4x5 @ BW+10, or 5,5,4 @ ... when they differ."""
    if not sets:
        return "-"
    work = [f"{s['seconds']}s" if s.get("seconds") else f"{s.get('reps', '?')}{'+' if s.get('amrap') else ''}" for s in sets]
    loads = {s.get("load") for s in sets}
    w = f"{len(sets)}x{work[0]}" if len(set(work)) == 1 else ",".join(work)
    if len(loads) == 1:
        lo = load_str(next(iter(loads)), load_type)
        return f"{w} @ {lo}" if lo else w
    return w + " @ " + ",".join(load_str(s.get("load"), load_type) or "-" for s in sets)


def expand_sets(block):
    out = []
    for s in block.get("sets", []):
        out.extend([s] * int(s.get("repeat", 1) or 1))
    return out


def print_context(ctx):
    today = ctx["today"]
    print(f"CRUX CONTEXT  today {day(today)}  window {ctx['days']} days  generated {ctx['generated_at']}")
    plan = ctx.get("plan")
    exercises = (plan or {}).get("exercises", {})

    print("\n== PLAN")
    if not plan:
        print("  (no plan published yet)")
    else:
        print(f"  v{plan['version']} \"{plan.get('title')}\"  {plan.get('start_date', '?')} -> {plan.get('end_date', '?')}"
              f"  published {plan.get('published_at', '?')[:16]}")
        if plan.get("goal"):
            print(f"  goal: {plan['goal']}")
        sessions = plan.get("sessions", [])
        past = [s for s in sessions if s["date"] < today]
        upcoming = [s for s in sessions if s["date"] >= today]
        print(f"  sessions: {len(sessions)} total, {len(past)} past, {len(upcoming)} today/upcoming")
        for s in upcoming[:4]:
            print(f"  next: {day(s['date'])}  {s.get('kind')}  \"{s.get('title')}\"  [{s['id']}]")

    w = ctx.get("wellness")
    print("\n== WELLNESS (Huawei via intervals.icu)")
    if w is None:
        print(f"  unavailable: {ctx.get('wellness_error')}")
    elif not w:
        print("  (no records)")
    else:
        print("  date            sleep  score  sleepHR  restHR  HRV   weight  vo2max  steps")
        for r in sorted(w, key=lambda r: r["id"])[-21:]:
            sl = f"{r['sleepSecs'] / 3600:.1f}h" if r.get("sleepSecs") else "-"
            print(f"  {day(r['id']):14}  {sl:5}  {r.get('sleepScore') or '-':>5}  {r.get('avgSleepingHR') or '-':>7}"
                  f"  {r.get('restingHR') or '-':>6}  {r.get('hrv') or '-':>4}  {r.get('weight') or '-':>6}"
                  f"  {r.get('vo2max') or '-':>6}  {r.get('steps') or '-':>5}")

    acts = ctx.get("activities")
    print("\n== ACTIVITIES (watch via intervals.icu)")
    if acts is None:
        print(f"  unavailable: {ctx.get('activities_error')}")
    elif not acts:
        print("  (none)")
    else:
        for a in sorted(acts, key=lambda a: a.get("start_date_local", "")):
            dist = f"{a['distance'] / 1000:.2f}km" if a.get("distance") else ""
            pace = ""
            if a.get("type") in RUN_TYPES and a.get("distance") and a.get("moving_time"):
                p = a["moving_time"] / (a["distance"] / 1000)
                pace = f"{int(p // 60)}:{int(p % 60):02d}/km"
            hr = f"HR {a.get('average_heartrate') or '-'}/{a.get('max_heartrate') or '-'}"
            load = f"load {a['icu_training_load']}" if a.get("icu_training_load") else ""
            rpe = f"rpe {a['icu_rpe']}" if a.get("icu_rpe") else ""
            print(f"  {day(a.get('start_date_local', '')[:10])}  {a.get('type', '?'):14} \"{a.get('name', '')}\""
                  f"  {hms(a.get('moving_time'))}  {dist}  {pace}  {hr}  {load}  {rpe}".rstrip())
            zones = a.get("icu_hr_zone_times")
            if zones and any(zones):
                print(f"      HR zone mins: {[round(z / 60) for z in zones]}")

    logs = ctx.get("logs") or []
    print("\n== GYM / SESSION LOGS (from the app)")
    if not logs:
        print("  (none)")
    for lg in logs:
        dur = ""
        if lg.get("started_at") and lg.get("finished_at"):
            try:
                d = dt.datetime.fromisoformat(lg["finished_at"]) - dt.datetime.fromisoformat(lg["started_at"])
                dur = f"{int(d.total_seconds() // 60)} min"
            except ValueError:
                pass
        print(f"  {day(lg['date'])}  \"{lg.get('title', '')}\"  [{lg.get('status', '?')}]"
              f"  RPE {lg.get('rpe') or '-'}  {dur}  session={lg.get('session_id') or 'freestyle'} plan=v{lg.get('plan_version') or '?'}")
        by_ex = {}
        for s in lg.get("sets", []):
            by_ex.setdefault((s.get("block", 0), s.get("exercise")), []).append(s)
        for (_, eid), sets in sorted(by_ex.items(), key=lambda kv: kv[0][0]):
            lt = sets[0].get("load_type") or exercises.get(eid, {}).get("load_type", "total")
            name = sets[0].get("name") or exercises.get(eid, {}).get("name", eid)
            target = [{"reps": s.get("target_reps"), "seconds": s.get("target_seconds"), "load": s.get("target_load")}
                      for s in sets if s.get("target_reps") or s.get("target_seconds")]
            target = [{k: v for k, v in t.items() if v is not None} for t in target]
            done = [s for s in sets if s.get("done") and not s.get("skipped")]
            skipped = len([s for s in sets if s.get("skipped")])
            got = fmt_target([{k: s.get(k) for k in ("reps", "seconds", "load") if s.get(k) is not None} for s in done], lt) if done else "none"
            rpes = ",".join(f"{s['rpe']:g}" for s in done if s.get("rpe") is not None)
            line = f"    {name:24} target {fmt_target(target, lt):22} done {got}"
            if rpes:
                line += f"  rpe {rpes}"
            if skipped:
                line += f"  ({skipped} skipped)"
            print(line)
        if lg.get("notes"):
            print(f"    notes: {lg['notes']}")

    if plan:
        print("\n== COMPLIANCE (plan sessions up to today)")
        log_by_session = {}
        for lg in logs:
            if lg.get("session_id"):
                log_by_session[lg["session_id"]] = lg
        runs_by_date = {}
        for a in acts or []:
            if a.get("type") in RUN_TYPES:
                runs_by_date.setdefault(a["start_date_local"][:10], []).append(a)
        window_start = (dt.date.fromisoformat(today) - dt.timedelta(days=ctx["days"] - 1)).isoformat()
        for s in plan.get("sessions", []):
            if not (window_start <= s["date"] <= today) or s.get("kind") == "rest":
                continue
            lg = log_by_session.get(s["id"])
            if lg:
                state = f"logged [{lg.get('status')}] RPE {lg.get('rpe') or '-'}"
            elif s.get("kind") == "run" and runs_by_date.get(s["date"]):
                r = runs_by_date[s["date"]][0]
                state = f"watch run {r.get('distance', 0) / 1000:.1f}km {hms(r.get('moving_time'))}"
            elif s["date"] == today:
                state = "today, not done yet"
            else:
                state = "MISSED / not logged"
            print(f"  {day(s['date'])}  {s['id']:10} {s.get('kind', ''):9} \"{s.get('title')}\" -> {state}")


def print_plan_summary(plan):
    ex = plan.get("exercises", {})
    print(f"\"{plan.get('title')}\"  {plan.get('start_date', '?')} -> {plan.get('end_date', '?')}")
    for s in plan.get("sessions", []):
        print(f"  {day(s['date'])}  {s['id']:10} {s.get('kind', 'strength'):9} {s.get('title')}")
        for b in s.get("blocks", []):
            e = ex.get(b["exercise"], {})
            print(f"      {e.get('name', b['exercise']):26} {fmt_target(expand_sets(b), e.get('load_type', 'total'))}")
        if s.get("run", {}).get("workout"):
            for line in s["run"]["workout"].strip().splitlines():
                print(f"      | {line}")


# ---------------------------------------------------------------- commands


def read_plan(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def cmd_status(_):
    _, st = call("GET", "/api/status")
    print(json.dumps(st, indent=2))
    _, icu = call("GET", "/api/settings/icu")
    print("intervals.icu:", json.dumps(icu))


def cmd_context(a):
    _, ctx = call("GET", f"/api/context?days={a.days}")
    if a.json:
        print(json.dumps(ctx, indent=1))
    else:
        print_context(ctx)


def cmd_plan(a):
    if a.action == "pull":
        code, plan = call("GET", "/api/plan", ok=(200, 404))
        if code == 404:
            sys.exit("no plan published yet")
        out = a.output or os.path.join(HERE, "plans", "current.json")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(plan, f, indent=2, ensure_ascii=False)
        print(f"v{plan['version']} -> {out}")
    elif a.action == "history":
        _, rows = call("GET", "/api/plans")
        for r in rows:
            print(f"  v{r['version']:<4} {r['created_at']}  {r['title']}")
    elif a.action in ("check", "push"):
        if not a.file:
            sys.exit(f"plan {a.action} needs a FILE")
        plan = read_plan(a.file)
        code, res = call("PUT", "/api/plan?dry_run=true", plan, ok=(200, 422))
        for w in res.get("warnings", []):
            print("warning:", w)
        for e in res.get("errors", []):
            print("ERROR:", e)
        if res.get("errors"):
            sys.exit(f"{len(res['errors'])} error(s) - not published")
        print_plan_summary(plan)
        if a.action == "check":
            print("OK - valid")
            return
        _, res = call("PUT", f"/api/plan?push_icu={'false' if a.no_icu else 'true'}", plan)
        print(f"published v{res['version']}")
        icu = res.get("icu")
        if icu is not None:
            print("intervals.icu:", json.dumps(icu))


def cmd_logs(a):
    _, logs = call("GET", f"/api/logs?days={a.days}")
    print(json.dumps(logs, indent=1, ensure_ascii=False))


def cmd_pair(_):
    _, res = call("POST", "/api/pair/new", {})
    print(f"Pairing code: {res['code']}  (valid {res['expires_in'] // 60} min, single use)")
    print("In the app: Settings -> Pair with server -> enter the code.")


def cmd_icu_key(_):
    print("intervals.icu -> Settings -> Developer Settings -> API key.")
    key = getpass.getpass("API key (hidden): ").strip()
    if not key:
        sys.exit("nothing entered")
    _, res = call("POST", "/api/settings/icu", {"api_key": key, "athlete_id": "0"})
    print(f"Stored. Athlete {res['athlete_id']} {res.get('athlete_name') or ''}")


def cmd_icu_push(a):
    flags = f"force={'true' if a.force else 'false'}&recreate={'true' if a.recreate else 'false'}"
    _, res = call("POST", f"/api/icu/push?{flags}", {})
    print(json.dumps(res))


def main():
    p = argparse.ArgumentParser(description="Crux coach CLI")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    c = sub.add_parser("context")
    c.add_argument("--days", type=int, default=28)
    c.add_argument("--json", action="store_true")
    c.set_defaults(fn=cmd_context)
    pl = sub.add_parser("plan")
    pl.add_argument("action", choices=["check", "push", "pull", "history"])
    pl.add_argument("file", nargs="?")
    pl.add_argument("-o", "--output")
    pl.add_argument("--no-icu", action="store_true")
    pl.set_defaults(fn=cmd_plan)
    lg = sub.add_parser("logs")
    lg.add_argument("--days", type=int, default=28)
    lg.set_defaults(fn=cmd_logs)
    sub.add_parser("pair").set_defaults(fn=cmd_pair)
    sub.add_parser("icu-key").set_defaults(fn=cmd_icu_key)
    ip = sub.add_parser("icu-push")
    ip.add_argument("--force", action="store_true", help="re-save every event, even unchanged ones")
    ip.add_argument("--recreate", action="store_true",
                    help="delete and recreate every event (needed after changing intervals.icu sport settings)")
    ip.set_defaults(fn=cmd_icu_push)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()

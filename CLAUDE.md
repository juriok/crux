# Crux: a personal training system

Claude Code is the **coach**. It reads the athlete's training data, writes the plan and
publishes it to their home server. The Android app (Crux) only runs the plan:
guided gym sessions with a rest timer, quick logs for runs and climbing days,
and recovery data from their Huawei Watch GT 5 Pro.

```
Huawei GT 5 Pro ─► Huawei Health ─► intervals.icu ─┐ runs, sleep, resting HR, HRV
                                         ▲          │
                         planned runs ───┘          ▼
Claude Code ──coach.py──► Crux server (home box) ◄──► Crux app on the phone
            plan JSON      plan + workout logs         workout logs (sets, reps, kg, RPE, notes)
```

## When the athlete asks for a plan, an adjustment, or "how am I doing"

1. Read `coach/athlete.md`: profile, goals, constraints, past decisions.
2. `python coach/coach.py context --days 28` shows plan compliance, every logged set
   against its target, watch runs with HR, and sleep/resting HR. Add `--json` for raw data.
3. Decide using the principles below. When something important is unknown
   (availability, pain, a missed week), ask before planning around a guess.
4. Write `coach/plans/YYYY-MM-DD-<slug>.json` (format: `coach/PLAN_FORMAT.md`).
   Start from the current plan (`coach.py plan pull`) when adjusting, and **keep
   session ids stable**: logs point at them.
5. `python coach/coach.py plan check FILE`, fix errors, then `plan push FILE`.
   The push also replaces the future sessions in the intervals.icu calendar.
6. Add a dated entry to the decision log in `coach/athlete.md`: what changed and why.
7. Tell the athlete what changed in a few plain lines. They read it in the app's coach notes too.

## Coaching principles

- **Goal: climbing power.** Max pulling strength (weighted pull-ups 3–5 reps),
  lock-offs, explosive pulls, grip and finger endurance, core tension (hanging
  leg raises → front-lever progressions), plus antagonists and shoulder health
  (push-ups/dips, external rotations). Legs get maintenance strength, not size.
- **Equipment: weights gym only**: no hangboard or wall listed. Grip comes from
  bar hangs (open hand, add weight with a belt), towel hangs, plate pinches,
  farmer's carries and wrist curls. Ask before assuming anything else.
- **Progression** on pulls: double progression. When every set hits the target
  reps at RPE ≤ 8, add 2.5 kg next time. A set at RPE 10, or missed reps two
  sessions running, means hold or drop 5%.
- **Fingers and elbows are the injury risk.** Any pain note → remove the
  aggravating exercise, replace it with something pain-free, ask about it. Never
  progress grip load and pulling load in the same week after a pain note.
- **Blocks**: 3 build weeks + 1 deload (about 60% volume, same intensity).
  Plan 1–2 weeks in detail and re-plan weekly from the logs.
- **Running**: about 80% easy (Z1–Z2, conversational). Hard runs ≥ 24 h away from
  heavy leg work, and not the day before max pulling. **Every run step uses an HR
  target**: pace steps never reach the Huawei watch (see `coach/PLAN_FORMAT.md`).
  Fast reps get a wide `60-110% LTHR` range, with the target pace in the run text.
- **Recovery**: resting HR ≥ 5 above its 7-day average, HRV ≥ 15% down, or sleep
  under 6 h on two or more days → shorten or ease the next hard session. The app
  shows the same signals on its Today screen.
- Session RPE and notes are the main feedback channel. Read them all.

## Layout

| Path | What |
|---|---|
| `app/` | Android app, Kotlin + Compose + Room. `.\build.ps1` builds a signed release |
| `dist/` | Built APKs to copy to the phone (git-ignored) |
| `server/` | FastAPI + SQLite in Docker on the home server |
| `coach/coach.py` | Coach CLI (stdlib only). Reads `coach/config.json` (git-ignored: url + token) |
| `coach/PLAN_FORMAT.md` | Plan and log JSON reference |
| `coach/athlete.md` | Athlete profile and decision log (git-ignored). Keep it current |
| `coach/plans/` | Every plan published, by date (git-ignored) |

## Server operations

Home server `<user>@<server-ip>` (Tailscale), directory `~/crux`, container
`crux`, port 8094. Public HTTPS (what the phone uses):
`https://<machine>.<tailnet>.ts.net/crux` through Tailscale Funnel. The real
values are in `CLAUDE.local.md` and `server.properties` (both git-ignored). The server
accepts paths with or without the `/crux` prefix.

```bash
# deploy server changes
scp -r server/app server/Dockerfile server/docker-compose.yml server/requirements.txt <user>@<server-ip>:crux/
ssh <user>@<server-ip> 'cd ~/crux && docker compose up -d --build'
curl -s http://<server-ip>:8094/api/status

# backup (SQLite in rollback-journal mode, so the .backup API gives a consistent copy)
ssh <user>@<server-ip> 'docker exec crux python -c "import sqlite3; s=sqlite3.connect(\"/data/crux.db\"); d=sqlite3.connect(\"/data/crux-backup.db\"); s.backup(d)"'
scp <user>@<server-ip>:crux/db/crux-backup.db ./crux-backup.db
```

- `server/.env` holds `CRUX_TOKEN` (git-ignored, copied to the server). The phone
  gets the token by pairing: `coach.py pair` prints a 6-digit code, valid 10
  minutes, single use.
- The intervals.icu API key lives only in the server database. Set it with
  `coach.py icu-key` (hidden prompt), and it is verified against intervals.icu.
- Huawei gets planned runs from intervals.icu's "upload next workout" (one at a
  time, no API to trigger it). An edit to a run that was **already sent** doesn't
  replace it on the watch. What worked: delete the old course in
  Huawei Health (Exercise → My running courses → Custom → trash), and the edited
  version arrives within minutes. Fallback: **Upload to Huawei** in that workout's
  menu on intervals.icu (not the top-bar Upload, which is for finished activities).
  So publish run changes before the run becomes "next" when possible.
- **Pace steps never reach the watch.** In runs that mixed HR and pace steps, the
  Huawei copy kept only the HR steps. That stayed true after setting a Run threshold
  pace and recreating the events. HR-only runs with the event's
  device `target` = HR arrive complete (confirmed on the watch). The
  server sets `target` from the steps, and `plan check` warns on mixed runs. After
  changing intervals.icu sport settings, run `coach.py icu-push --recreate`.
- The box drops its uplink at night. The app is offline-first and queues logs;
  the server serves stale intervals.icu data rather than none.

## App notes

- Offline-first: screens read Room only. `Repo.refresh()` uploads finished
  logs, then pulls the plan (ETag), intervals.icu data and server-side logs.
  WorkManager retries when the network returns, and also syncs every 6 h.
- A workout snapshots its session when the first set is logged, so a plan push
  mid-workout cannot reshuffle it.
- `RestTimerService` is a `specialUse` foreground service. It holds a partial
  wakelock only during a rest countdown, vibrates when rest is over and posts a
  normal notification, which Huawei Health mirrors to the watch.
- Release signing: `keystore.properties` + `%USERPROFILE%\android-tools\crux-release.keystore`
  (backup next to it). Lose both and installed copies can no longer be updated.

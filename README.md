# Crux

A personal training app where the coach is Claude Code.

You tell Claude Code what you want ("more pulling power for climbing, three gym
days and two runs a week"). It reads what you actually did, writes the next
weeks of training and publishes them to your home server. The phone app guides
each gym session set by set and sends the results back. Runs reach your Huawei
watch through intervals.icu.

## What the app does

- **Today**: the session for the day, recovery from last night (sleep, resting
  HR, HRV vs. your 7-day average), what's coming up, anything not yet logged.
- **Guided workouts**: exercise, set, target (reps · kg · RPE), technique cues,
  and what you did last time. Log reps and weight with big +/− buttons. The rest
  timer starts by itself, keeps going with the screen off, and buzzes the phone
  (and the watch, with notification mirroring on) when the next set is due.
  Timed sets (hangs, holds, carries) get a count-in and countdown, or a
  stopwatch for max holds. Supersets alternate automatically.
- **Runs and climbing days**: the plan and the structured run (also on the
  watch). Mark it done or skipped with an RPE and a note. A watch run on that
  day counts by itself.
- **Plan**: the whole block week by week with done/missed status, plus the
  coach's notes.
- **History**: every logged workout set by set, each exercise's progress, and
  your watch activities.
- **Offline**: everything works at the gym with no signal. Logs upload when
  the phone is back online.

## First-time setup

1. **Public address for the server** (once, needs your sudo password on the server):

   ```bash
   ssh -t <user>@<server-ip> "sudo tailscale funnel --bg --set-path /crux http://127.0.0.1:8094"
   ```

2. **Server address for the app**: copy `server.properties.example` to
   `server.properties` (git-ignored) and fill in your Funnel URL, then build
   (see below). You can also type the address in the app's Settings.

3. **Install** `dist/Crux-1.0.apk` on the phone (copy it over and open it, or
   `adb install -r dist\Crux-1.0.apk`). Allow notifications when asked.

4. **Pair**: on the PC run `python coach/coach.py pair`, then in the app go to
   Settings → enter the 6-digit code → Pair. The plan downloads straight away.

5. **Watch data** (intervals.icu, free):
   - sign up at intervals.icu, then Settings → Connections → HUAWEI Health → connect
   - in Huawei Health: Me → 3rd party services → enable **Health Service Kit**
   - in intervals.icu, Settings: set your running max HR (and LTHR once the 20-min test is done)
   - intervals.icu → Settings → Developer Settings → copy the API key, then on the PC:
     `python coach/coach.py icu-key` (the key is typed hidden and stored only on the server)

6. **Rest alerts on the wrist**: Huawei Health → Devices → your watch →
   Notifications → turn on Crux.

## Building

Uses the portable toolchain in `%USERPROFILE%\android-tools`.

```powershell
.\build.ps1                    # signed release -> app\build\outputs\apk\release\
.\build.ps1 testDebugUnitTest  # plan/log contract tests
```

Copy the release APK to `dist\Crux-<version>.apk`. Signing uses
`keystore.properties` (git-ignored) and `crux-release.keystore`, with a backup
in `android-tools`. Keep both; without them an installed copy can't be updated.

## Privacy

Your training data lives on your phone, on your own server and on
intervals.icu. Nothing goes to Google backup. The server needs a token for
every endpoint except a health check. The token reaches the phone only through
a single-use pairing code. The intervals.icu key never leaves the server.

See `CLAUDE.md` for how the coaching loop and operations work, and
`coach/PLAN_FORMAT.md` for the plan format.

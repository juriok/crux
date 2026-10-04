# Plan format

A plan is one JSON document. `coach.py plan push` validates it on the server
(same checks as `plan check`), stores it as a new version, and mirrors every
session dated today or later into the intervals.icu calendar. The phone picks
it up on its next sync.

Every push **replaces** the whole plan. Keep past sessions in it while they are
still useful context on the phone (the Plan tab shows them as done/missed); drop
old weeks once a block is over.

## Top level

| field | type | |
|---|---|---|
| `title` | string, required | "Pulling power – block 1" |
| `goal` | string | one or two sentences, shown on the Plan tab |
| `coach_notes` | string | shown under "Coach notes". Blank line = new paragraph, lines starting `- ` = bullets |
| `start_date`, `end_date` | `YYYY-MM-DD` | display only |
| `exercises` | object | exercise library, keyed by id (`[A-Za-z0-9_-]{1,48}`) |
| `sessions` | list | in date order |

`version` and `published_at` are added by the server; anything you put there is overwritten.

## Exercise

```json
"weighted_pullup": {
  "name": "Weighted pull-up",
  "load_type": "added",
  "load_note": "",
  "cues": ["Full hang at the bottom", "Chin over the bar", "No kipping"],
  "notes": "Belt or a dumbbell between the feet."
}
```

`load_type` decides how `load` is shown and entered:

| load_type | `load` means | shown as |
|---|---|---|
| `total` (default) | kg on the bar / dumbbell | `60 kg` (+ `load_note`, e.g. "per hand") |
| `added` | kg on top of bodyweight, 0 = bodyweight | `BW`, `BW +10 kg` |
| `assisted` | kg of assistance (band/machine) | `10 kg assist` |
| `bodyweight` | not used | nothing |

## Session

```json
{
  "id": "b1-w1-pull",
  "date": "2026-10-12",
  "kind": "strength",
  "title": "Max pulling",
  "summary": "Heavy pulls, core, antagonists",
  "duration_min": 60,
  "optional": false,
  "notes": "Free text shown at the top of the session.",
  "warmup": ["5 min row", "Band pull-aparts 2 × 15"],
  "blocks": [ ... ],
  "cooldown": ["Forearm stretches"],
  "run": { ... }
}
```

- `id` must be unique **and stable**: logs point at it. If you republish a plan
  with a session moved to another day, keep its id. Never reuse an id for a
  different session.
- `kind`: `strength`, `run`, `climb`, `mobility`, `walk`, `hike`, `rest`, `other`.
  Sessions with `blocks` get the guided workout in the app; everything else is
  logged with a quick done/skipped + RPE + notes. `rest` is not shown.
- intervals.icu calendar types: run→Run, strength→WeightTraining,
  climb→RockClimbing, mobility→Yoga, walk/hike→Walk/Hike. **Only Run, Walk and
  Hike reach the Huawei watch**, and intervals.icu sends only the *next* one.

## Block

```json
{
  "exercise": "weighted_pullup",
  "superset": "A",
  "note": "Add 2.5 kg next time if every set was RPE 8 or less.",
  "sets": [
    {"reps": 5, "load": 10, "rest_s": 180, "rpe": 8, "repeat": 4}
  ]
}
```

Consecutive blocks with the same `superset` label alternate set by set
(A1, A2, A1, A2…). `rest_s` is the rest *after* that set, so in a superset give
the first exercise a short rest (e.g. 20) and the last a full one.

## Set

| field | | |
|---|---|---|
| `reps` | int 1–100 | target reps |
| `seconds` | int 1–3600 | timed set (hang, hold, carry). Use reps **or** seconds |
| `load` | number, kg | omit to let the athlete choose (the app pre-fills last time's load) |
| `rest_s` | int 0–900, default 90 | rest after the set; drives the timer and the watch buzz |
| `rpe` | 1–10 | target effort |
| `amrap` | bool | reps: "as many as possible" (`reps` becomes the minimum). seconds: "hold as long as possible" (stopwatch) |
| `repeat` | int 1–20 | shorthand for N identical sets |
| `note` | string | shown under the target |

A set with neither `reps` nor `seconds` needs a `note` saying what to do.

## Run

```json
"run": {
  "type": "easy",
  "target": "Conversational. Walk the hills if needed.",
  "distance_km": 7,
  "workout": "Warmup\n- 10m Z1-Z2 HR\n\nMain set 5x\n- 3m 4:30/km Pace\n- 2m Z1 HR\n\nCooldown\n- 10m Z1 HR"
}
```

`workout` is intervals.icu workout text and is what reaches the watch:

- `- <duration> <target>`; durations `10m`, `30s`, `5m30s`, distances `2km`, `400mtr` (`m` is minutes)
- targets: `Z2 HR`, `Z1-Z2 HR`, `90-95% LTHR`, `Z3 Pace`, `5:00/km Pace`
- repeats: a header line ending in `Nx` (`Main set 5x`) with blank lines around the block
- **One target type per run, and use HR.** Through intervals.icu → Huawei, a run that
  mixed HR and pace steps lost every pace step (strides, 800 m reps), even with a
  threshold pace set. HR steps always arrive. So:
  - easy/steady parts: `Z1-Z2 HR`, `Z2 HR`; tempo: `90-100% LTHR`
  - fast reps and their recoveries: a wide `60-110% LTHR` (the watch never alarms; the
    step's time or distance is what counts) and put the target pace in `target` text
  - the server sets the event's device `target` from the steps and warns on mixed runs
- Repeats (`Nx` headers) are fine: the server writes them out step by step.
  Keep steps ≥ 30 s.
- HR/pace zones come from the athlete's intervals.icu settings (LTHR, threshold pace)

## Logs (phone → server)

What `coach.py context` summarises. One per session done:

```json
{
  "id": "uuid", "date": "2026-10-12", "session_id": "b1-w1-pull", "plan_version": 4,
  "title": "Max pulling", "kind": "strength",
  "started_at": "2026-10-12T18:02:11+02:00", "finished_at": "2026-10-12T19:01:40+02:00",
  "status": "done | partial | skipped",
  "rpe": 8, "notes": "Left elbow a bit sore on the last set",
  "sets": [
    {"exercise": "weighted_pullup", "name": "Weighted pull-up", "load_type": "added",
     "block": 0, "set": 0, "reps": 5, "load": 10, "rpe": 8, "done": true, "skipped": false,
     "target_reps": 5, "target_load": 10, "logged_at": "..."}
  ]
}
```

`block` and `set` are 0-based positions in the session as it was when the
workout started (the phone snapshots it).

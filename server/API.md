# FitAir server API (v1)

Auth: header `X-Api-Key: <key>` on every request. Server creates `server/.apikey` on first run and prints it.
All times are epoch milliseconds (UTC). All bodies JSON. Writes are idempotent upserts on the natural key.

## POST /ingest
Body: `{"type": "<type>", "records": [ ... ]}`  -> `{"ok": true, "received": n}`
Types and record shapes (`origin` = Health Connect data origin package; key in parentheses):
- `heart_rate`        {"t", "bpm", "origin"}                       (t, origin)   one entry per sample
- `steps`             {"start", "end", "count", "origin"}          (start, end, origin)
- `distance`          {"start", "end", "meters", "origin"}         (start, end, origin)
- `total_calories`    {"start", "end", "kcal", "origin"}           (start, end, origin)
- `resting_hr`        {"t", "bpm", "origin"}                       (t, origin)
- `hrv`               {"t", "rmssd", "origin"}                     (t, origin)
- `respiratory_rate`  {"t", "rate", "origin"}                      (t, origin)
- `sleep`             {"start", "end", "origin", "stages": [{"start","end","stage"}]}   (start, origin)
   stage = Health Connect SleepSessionRecord.STAGE_TYPE_* int
- `exercise`          {"start", "end", "type", "title", "origin"}  (start, end, origin)   type = ExerciseSessionRecord.EXERCISE_TYPE_* int

## GET /health            -> {"ok": true, "counts": {"<type>": n}}
## GET /watermark?type=T  -> {"type": T, "maxT": <ms or null>}   (latest t/start/end stored; phone resumes from here, minus a safety overlap)

## Query endpoints used by the coach (all return JSON)
- GET /summary/day?date=YYYY-MM-DD&tz=Europe/Stockholm
    -> {date, steps, distance_m, active_minutes_est, resting_hr, hrv_rmssd_night_mean, hr_mean, hr_min, hr_max,
        sleep_minutes, sleep_stages_minutes{stage:min}, exercise_count, kcal_total, readiness}
- GET /summary/range?from=YYYY-MM-DD&to=YYYY-MM-DD&tz=...   -> {days: [summary/day objects]}
- GET /hr?from_ms=&to_ms=&bucket_s=60    -> {bucket_s, points: [{t, min, mean, max, n}]}   (bucket_s=0 -> raw samples)
- GET /workouts?from=YYYY-MM-DD&to=YYYY-MM-DD&tz=...
    -> {workouts: [{start,end,type,title,duration_min,hr_mean,hr_max,hr_recovery_1min,drift_pct,minutes_in_zone{z1..z5}}]}
- GET /readiness?date=YYYY-MM-DD&tz=...
    -> {date, score(0-100), components{sleep, hrv, resting_hr, load}, baseline{...}, notes[]}
- GET /baselines?tz=...  -> {resting_hr_28d_mean/sd, hrv_28d_mean/sd, sleep_28d_mean_min, ...}
Steps must be de-duplicated across origins: per minute take the origin with the larger count, not the sum.

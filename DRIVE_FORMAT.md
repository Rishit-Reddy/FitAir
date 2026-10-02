# FitAir Drive export format (v1)

The phone exports its SQLite data to one Google Drive folder named `FitAir` (created by the app, scope `drive.file`).
Layout is flat (no subfolders):

- `<type>_<YYYY-MM-DD>.jsonl.gz` — one file per data type per UTC day. Gzip of newline-delimited JSON, one record per line.
  The day is the UTC date of the record's `t` (or `start_ms`). A file is the complete set of that day's rows for that type.
- `manifest.json` — `{"version":1,"exported_at":<ms>,"tz":"<device tz>","files":{"<filename>":{"rows":n,"max_ms":ms}}}`

Types and record fields (exactly the phone DB columns, all times epoch ms UTC):
- heart_rate        {t, bpm, origin}                                                 (RAW samples, kept only inside exercise windows [start-5min, end+5min])
- heart_rate_30s    {t30, mean, min, max, n, origin}                                (all-day 30 s buckets; t30 = bucket start ms, aligned to 30000; file `heart_rate_30s_<YYYY-MM-DD>.jsonl.gz`, day = UTC date of t30)
- steps             {start_ms, end_ms, count, origin}
- distance          {start_ms, end_ms, meters, origin}
- total_calories    {start_ms, end_ms, kcal, origin}
- resting_hr        {t, bpm, origin}
- hrv               {t, rmssd, origin}
- respiratory_rate  {t, rate, origin}
- sleep             {start_ms, end_ms, origin, stages:[{start_ms, end_ms, stage}]}   (stage = Health Connect SleepSessionRecord.STAGE_TYPE_* int; day = UTC date of start_ms)
- exercise          {start_ms, end_ms, type, title, origin}                          (type = ExerciseSessionRecord.EXERCISE_TYPE_* int)

Single (non-daily) file:
- `daily_metrics.jsonl.gz` — whole `daily_metrics` table, one JSON object per row, ordered by date, rewritten when its content hash changes.
  Fields: date (YYYY-MM-DD, local), tz, computed_ms, readiness, readiness_json, sleep_score, sleep_json, load_trimp, acute_load, chronic_load, acwr, rhr, hrv, sleep_min, steps, insights_json (`*_json` are JSON strings; null columns are `null`). Importing replaces the whole table.
  Manifest entry: rows, max_ms = max computed_ms.

Idempotent: importing a day file replaces that day's rows for that type. Files for past days are rewritten only if content changed.

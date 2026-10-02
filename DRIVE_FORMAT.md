# FitAir Drive export format (v1)

The phone exports its SQLite data to one Google Drive folder named `FitAir` (created by the app, scope `drive.file`).
Layout is flat (no subfolders):

- `<type>_<YYYY-MM-DD>.jsonl.gz` — one file per data type per UTC day. Gzip of newline-delimited JSON, one record per line.
  The day is the UTC date of the record's `t` (or `start_ms`). A file is the complete set of that day's rows for that type.
- `manifest.json` — `{"version":1,"exported_at":<ms>,"tz":"<device tz>","files":{"<filename>":{"rows":n,"max_ms":ms}}}`

Types and record fields (exactly the phone DB columns, all times epoch ms UTC):
- heart_rate        {t, bpm, origin}
- steps             {start_ms, end_ms, count, origin}
- distance          {start_ms, end_ms, meters, origin}
- total_calories    {start_ms, end_ms, kcal, origin}
- resting_hr        {t, bpm, origin}
- hrv               {t, rmssd, origin}
- respiratory_rate  {t, rate, origin}
- sleep             {start_ms, end_ms, origin, stages:[{start_ms, end_ms, stage}]}   (stage = Health Connect SleepSessionRecord.STAGE_TYPE_* int; day = UTC date of start_ms)
- exercise          {start_ms, end_ms, type, title, origin}                          (type = ExerciseSessionRecord.EXERCISE_TYPE_* int)

Idempotent: importing a day file replaces that day's rows for that type. Files for past days are rewritten only if content changed.

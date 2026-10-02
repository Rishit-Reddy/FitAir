# FitAir Drive backup format (v2)

The phone keeps ONE backup file in Google Drive: `FitAir-backup.zip` at the root of My Drive (no folder; scope
`drive.file`, so the app only sees files it created). It is found by name and overwritten in place (resumable upload)
on every backup: daily when charging on unmetered network, or on "Back up now".

Zip contents:
- `fitair.db` — consistent SQLite snapshot of the phone database (made with `VACUUM INTO`; fallback: WAL checkpoint + file copy).
  Tables: heart_rate, steps, distance, total_calories, resting_hr, hrv, respiratory_rate, sleep, sleep_stage, exercise,
  hr_30s, daily_metrics, meta. All times are epoch ms UTC. Restore = open it as the app's `fitair.db`.
- `backup.json` — `{"version":2,"created_ms":<ms>,"app_version":"<name>","db_version":<int>,"row_counts":{"<table>":n,...}}`

Not included: shared preferences, API keys, tokens.

## Obsolete: per-day format (v1)

Versions up to 0.6.0 wrote a `FitAir` folder with `<type>_<YYYY-MM-DD>.jsonl.gz` files, `daily_metrics.jsonl.gz` and
`manifest.json`. That format is obsolete and no longer written or updated; existing files can be deleted.

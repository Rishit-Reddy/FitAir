# FitAir analysis: Drive export -> DuckDB (macOS)

Imports the files the phone exports to Google Drive (see `../DRIVE_FORMAT.md`) into a local DuckDB database for analysis.

## 1. Get the data onto your Mac

1. Install **Google Drive for desktop** (https://www.google.com/drive/download/), sign in with the same Google account the phone uses.
2. The app writes a `FitAir` folder in your Drive. Make sure it is available offline: in Finder right-click the folder -> *Offline access* -> *Available offline* (otherwise "streamed" files may not be fully downloaded).
3. Find the path (Drive for desktop mounts under CloudStorage):
   ```bash
   ls ~/Library/CloudStorage/            # shows GoogleDrive-<account>
   ls "$HOME/Library/CloudStorage/GoogleDrive-you@gmail.com/My Drive/FitAir"
   ```
   Note: the folder is created by the app with scope `drive.file`; it shows up in "My Drive" like any folder. If it was shared to you, add a shortcut to My Drive first.

## 2. Setup

```bash
cd analysis
python3 -m venv .venv && source .venv/bin/activate     # Python 3.11+
pip install -r requirements.txt
```

## 3. Run

```bash
python fitair_import.py \
  --src "$HOME/Library/CloudStorage/GoogleDrive-you@gmail.com/My Drive/FitAir" \
  --db ~/fitair.duckdb [--tz Europe/Stockholm] [--watch 300]
```

- Files `<type>_<YYYY-MM-DD>.jsonl.gz` are tracked in table `import_log` (name, size, mtime, sha256). Unchanged files are skipped; a changed file deletes that UTC day's rows for that type and inserts the new ones (in one transaction).
- Truncated/corrupt (partially synced) files are skipped with a warning and retried next run.
- `--watch N` re-runs every N seconds. The DB is closed between runs, so you can query it between passes (DuckDB allows only one read-write process at a time; the importer will fail to start if another process holds the file open).
- `--tz` sets the timezone used by the `daily_summary` view (rebuilt on each run).

## Schema

Raw tables mirror the export (epoch ms UTC): `heart_rate, resting_hr, hrv, respiratory_rate` (`t`), `steps, distance, total_calories, exercise` (`start_ms,end_ms`), `sleep` + `sleep_stage` (stage = Health Connect int), plus `import_log`. Primary keys are `(t, origin)`, `(start_ms[, end_ms], origin)`, etc.

Views with real timestamps (naive UTC `TIMESTAMP`): `heart_rate_v, resting_hr_v, hrv_v, respiratory_rate_v` (`t_utc`), `steps_v, distance_v, total_calories_v, exercise_v` (`start_utc, end_utc`), `sleep_v` (also `hours_in_bed`, `hours_asleep` = minus awake stages 1/3/7), `sleep_stage_v`, and `daily_summary` (`day, steps, resting_hr, mean_hr, sleep_hours`; local days in `--tz`; sleep is attributed to the day you wake up).

## Example queries

```sql
-- Nightly HRV trend (7-day rolling mean)
SELECT d, nightly, avg(nightly) OVER (ORDER BY d ROWS 6 PRECEDING) AS rolling7
FROM (SELECT CAST(t_utc AS DATE) AS d, avg(rmssd) AS nightly FROM hrv_v GROUP BY 1) ORDER BY d;

-- HR during exercise sessions
SELECT e.title, e.start_utc, round(avg(h.bpm),1) AS avg_bpm, max(h.bpm) AS max_bpm
FROM exercise_v e JOIN heart_rate_v h ON h.t_utc BETWEEN e.start_utc AND e.end_utc
GROUP BY ALL ORDER BY e.start_utc;

-- Daily overview
SELECT * FROM daily_summary ORDER BY day DESC LIMIT 14;

-- Time in each sleep stage per night
SELECT CAST(sleep_start_utc AS DATE) AS night, stage, sum(minutes) AS minutes
FROM sleep_stage_v GROUP BY ALL ORDER BY 1, 2;
```

## Opening the database

- DuckDB CLI (`brew install duckdb`): `duckdb ~/fitair.duckdb` (use `-readonly` while the watcher runs between passes).
- Python: `duckdb.connect(str(Path.home()/'fitair.duckdb'), read_only=True).sql("SELECT * FROM daily_summary").df()`
- pandas: `df = duckdb.connect(path, read_only=True).sql("SELECT * FROM hrv_v").df()` returns a DataFrame.

## Later: React dashboard

Put a tiny read-only API in front (e.g. FastAPI or Node + `duckdb` exposing `/daily`, `/hrv`, `/exercise` as JSON from the views above) and point the dashboard at it. Open the DB with `read_only=True` and run the importer on a schedule/`--watch`.

## Tests

`pytest tests` (synthetic day files; no Drive needed).

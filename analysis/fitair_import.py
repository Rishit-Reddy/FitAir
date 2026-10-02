#!/usr/bin/env python3
"""Import FitAir Drive export files (<type>_<YYYY-MM-DD>.jsonl.gz) into DuckDB.

See ../DRIVE_FORMAT.md. Idempotent: unchanged files (same size + mtime_ns +
sha256) are skipped; changed files replace that UTC day's rows for the type.
"""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import json
import re
import sys
import time
import zlib
from pathlib import Path
from zoneinfo import ZoneInfo

import duckdb

DAY_MS = 86_400_000
FILE_RE = re.compile(r"^([a-z_]+)_(\d{4}-\d{2}-\d{2})\.jsonl\.gz$")

# type -> (table, time column used for the day, columns, row builder keys)
POINT = {
    "heart_rate": ("bpm",),
    "resting_hr": ("bpm",),
    "hrv": ("rmssd",),
    "respiratory_rate": ("rate",),
}
INTERVAL = {
    "steps": ("count",),
    "distance": ("meters",),
    "total_calories": ("kcal",),
}
TYPES = list(POINT) + list(INTERVAL) + ["sleep", "exercise"]

SCHEMA = """
CREATE TABLE IF NOT EXISTS heart_rate (t BIGINT NOT NULL, bpm INTEGER NOT NULL, origin VARCHAR NOT NULL, PRIMARY KEY (t, origin));
CREATE TABLE IF NOT EXISTS resting_hr (t BIGINT NOT NULL, bpm INTEGER NOT NULL, origin VARCHAR NOT NULL, PRIMARY KEY (t, origin));
CREATE TABLE IF NOT EXISTS hrv (t BIGINT NOT NULL, rmssd DOUBLE NOT NULL, origin VARCHAR NOT NULL, PRIMARY KEY (t, origin));
CREATE TABLE IF NOT EXISTS respiratory_rate (t BIGINT NOT NULL, rate DOUBLE NOT NULL, origin VARCHAR NOT NULL, PRIMARY KEY (t, origin));
CREATE TABLE IF NOT EXISTS steps (start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, count BIGINT NOT NULL, origin VARCHAR NOT NULL, PRIMARY KEY (start_ms, end_ms, origin));
CREATE TABLE IF NOT EXISTS distance (start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, meters DOUBLE NOT NULL, origin VARCHAR NOT NULL, PRIMARY KEY (start_ms, end_ms, origin));
CREATE TABLE IF NOT EXISTS total_calories (start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, kcal DOUBLE NOT NULL, origin VARCHAR NOT NULL, PRIMARY KEY (start_ms, end_ms, origin));
CREATE TABLE IF NOT EXISTS sleep (start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, origin VARCHAR NOT NULL, PRIMARY KEY (start_ms, origin));
CREATE TABLE IF NOT EXISTS sleep_stage (sleep_start_ms BIGINT NOT NULL, origin VARCHAR NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, stage INTEGER NOT NULL, PRIMARY KEY (sleep_start_ms, origin, start_ms));
CREATE TABLE IF NOT EXISTS exercise (start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, type INTEGER, title VARCHAR, origin VARCHAR NOT NULL, PRIMARY KEY (start_ms, origin));
CREATE TABLE IF NOT EXISTS import_log (
  file_name VARCHAR PRIMARY KEY, data_type VARCHAR NOT NULL, day DATE NOT NULL,
  size_bytes BIGINT NOT NULL, mtime_ns BIGINT NOT NULL, sha256 VARCHAR NOT NULL,
  row_count INTEGER NOT NULL, imported_at TIMESTAMP NOT NULL);
"""


def create_views(con, tz: str) -> None:
    ZoneInfo(tz)  # validate
    tzl = "'" + tz.replace("'", "''") + "'"
    ts = lambda c: f"epoch_ms({c})"
    local = lambda c: f"CAST(timezone({tzl}, epoch_ms({c})::TIMESTAMPTZ) AS DATE)"
    # NOTE: epoch_ms(x)::TIMESTAMPTZ treats naive value as UTC (session TZ set to UTC).
    for t, (v,) in POINT.items():
        con.execute(f"CREATE OR REPLACE VIEW {t}_v AS SELECT {ts('t')} AS t_utc, {v}, origin FROM {t}")
    for t, (v,) in INTERVAL.items():
        con.execute(f"CREATE OR REPLACE VIEW {t}_v AS SELECT {ts('start_ms')} AS start_utc, {ts('end_ms')} AS end_utc, {v}, origin FROM {t}")
    con.execute(f"""CREATE OR REPLACE VIEW sleep_v AS
      SELECT s.start_ms, {ts('s.start_ms')} AS start_utc, {ts('s.end_ms')} AS end_utc, s.origin,
             (s.end_ms - s.start_ms) / 3600000.0 AS hours_in_bed,
             ((s.end_ms - s.start_ms) - COALESCE((SELECT SUM(g.end_ms - g.start_ms) FROM sleep_stage g
                WHERE g.sleep_start_ms = s.start_ms AND g.origin = s.origin AND g.stage IN (1, 3, 7)), 0)) / 3600000.0 AS hours_asleep
      FROM sleep s""")
    con.execute(f"""CREATE OR REPLACE VIEW sleep_stage_v AS
      SELECT {ts('sleep_start_ms')} AS sleep_start_utc, origin, {ts('start_ms')} AS start_utc, {ts('end_ms')} AS end_utc, stage,
             (end_ms - start_ms) / 60000.0 AS minutes FROM sleep_stage""")
    con.execute(f"CREATE OR REPLACE VIEW exercise_v AS SELECT {ts('start_ms')} AS start_utc, {ts('end_ms')} AS end_utc, type, title, origin FROM exercise")
    con.execute(f"""CREATE OR REPLACE VIEW daily_summary AS
      WITH days AS (
        SELECT {local('start_ms')} AS day FROM steps UNION SELECT {local('t')} FROM heart_rate
        UNION SELECT {local('t')} FROM resting_hr UNION SELECT {local('end_ms')} FROM sleep),
      st AS (SELECT {local('start_ms')} AS day, SUM(count) AS steps FROM steps GROUP BY 1),
      rh AS (SELECT {local('t')} AS day, AVG(bpm) AS resting_hr FROM resting_hr GROUP BY 1),
      hr AS (SELECT {local('t')} AS day, AVG(bpm) AS mean_hr FROM heart_rate GROUP BY 1),
      sl AS (SELECT CAST(timezone({tzl}, end_utc::TIMESTAMPTZ) AS DATE) AS day, SUM(hours_asleep) AS sleep_hours FROM sleep_v GROUP BY 1)
      SELECT d.day, st.steps, rh.resting_hr, hr.mean_hr, sl.sleep_hours
      FROM days d LEFT JOIN st USING (day) LEFT JOIN rh USING (day) LEFT JOIN hr USING (day) LEFT JOIN sl USING (day)
      ORDER BY d.day""")


def connect(db: str, tz: str = "Europe/Stockholm"):
    con = duckdb.connect(db)
    con.execute("SET TimeZone='UTC'")
    con.execute(SCHEMA)
    create_views(con, tz)
    return con


def _sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_records(p: Path) -> list[dict]:
    """Read and fully validate a gz file; raises on truncation/corruption."""
    recs = []
    with gzip.open(p, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                recs.append(json.loads(line))
    return recs


def _day_range(day: str) -> tuple[int, int]:
    d = dt.datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=dt.timezone.utc)
    s = int(d.timestamp() * 1000)
    return s, s + DAY_MS


def _insert(con, typ: str, recs: list[dict]) -> None:
    if typ in POINT:
        v = POINT[typ][0]
        rows = [(int(r["t"]), r[v], r.get("origin") or "") for r in recs]
        con.executemany(f"INSERT OR REPLACE INTO {typ} VALUES (?,?,?)", rows)
    elif typ in INTERVAL:
        v = INTERVAL[typ][0]
        rows = [(int(r["start_ms"]), int(r["end_ms"]), r[v], r.get("origin") or "") for r in recs]
        con.executemany(f"INSERT OR REPLACE INTO {typ} VALUES (?,?,?,?)", rows)
    elif typ == "exercise":
        rows = [(int(r["start_ms"]), int(r["end_ms"]), r.get("type"), r.get("title"), r.get("origin") or "") for r in recs]
        con.executemany("INSERT OR REPLACE INTO exercise VALUES (?,?,?,?,?)", rows)
    elif typ == "sleep":
        con.executemany("INSERT OR REPLACE INTO sleep VALUES (?,?,?)",
                        [(int(r["start_ms"]), int(r["end_ms"]), r.get("origin") or "") for r in recs])
        st = [(int(r["start_ms"]), r.get("origin") or "", int(s["start_ms"]), int(s["end_ms"]), int(s["stage"]))
              for r in recs for s in (r.get("stages") or [])]
        con.executemany("INSERT OR REPLACE INTO sleep_stage VALUES (?,?,?,?,?)", st)


def _delete_day(con, typ: str, day: str) -> None:
    lo, hi = _day_range(day)
    col = "t" if typ in POINT else "start_ms"
    if typ == "sleep":
        con.execute("DELETE FROM sleep_stage WHERE sleep_start_ms >= ? AND sleep_start_ms < ?", [lo, hi])
    con.execute(f"DELETE FROM {typ} WHERE {col} >= ? AND {col} < ?", [lo, hi])


def import_folder(con, src: Path, log=print) -> dict:
    stats = {"imported": 0, "skipped": 0, "failed": 0, "rows": 0}
    for p in sorted(src.glob("*.jsonl.gz")):
        m = FILE_RE.match(p.name)
        if not m or m.group(1) not in TYPES:
            continue
        typ, day = m.groups()
        try:
            dt.date.fromisoformat(day)
            stt = p.stat()
            prev = con.execute("SELECT size_bytes, mtime_ns, sha256 FROM import_log WHERE file_name=?", [p.name]).fetchone()
            if prev and prev[0] == stt.st_size and prev[1] == stt.st_mtime_ns:
                stats["skipped"] += 1
                continue
            digest = _sha256(p)
            if prev and prev[2] == digest:  # touched but identical content
                con.execute("UPDATE import_log SET size_bytes=?, mtime_ns=? WHERE file_name=?", [stt.st_size, stt.st_mtime_ns, p.name])
                stats["skipped"] += 1
                continue
            recs = read_records(p)
        except (OSError, EOFError, zlib.error, gzip.BadGzipFile, json.JSONDecodeError, UnicodeDecodeError, ValueError) as e:
            print(f"WARNING: skipping {p.name}: {type(e).__name__}: {e}", file=sys.stderr)
            stats["failed"] += 1
            continue
        try:
            con.execute("BEGIN")
            _delete_day(con, typ, day)
            _insert(con, typ, recs)
            con.execute("DELETE FROM import_log WHERE file_name=?", [p.name])
            con.execute("INSERT INTO import_log VALUES (?,?,?,?,?,?,?,?)",
                        [p.name, typ, day, stt.st_size, stt.st_mtime_ns, digest, len(recs), dt.datetime.now(dt.timezone.utc).replace(tzinfo=None)])
            con.execute("COMMIT")
        except Exception as e:  # bad record shape (missing field etc.)
            con.execute("ROLLBACK")
            print(f"WARNING: skipping {p.name}: bad records ({type(e).__name__}: {e})", file=sys.stderr)
            stats["failed"] += 1
            continue
        stats["imported"] += 1
        stats["rows"] += len(recs)
        log(f"imported {p.name} ({len(recs)} rows)")
    return stats


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--src", required=True, help="folder synced by Google Drive for desktop")
    ap.add_argument("--db", default="~/fitair.duckdb")
    ap.add_argument("--tz", default="Europe/Stockholm", help="timezone for daily_summary local days")
    ap.add_argument("--watch", type=int, metavar="N", help="re-run every N seconds")
    a = ap.parse_args(argv)
    src = Path(a.src).expanduser()
    if not src.is_dir():
        print(f"error: --src {src} is not a directory", file=sys.stderr)
        return 2
    db = str(Path(a.db).expanduser())
    while True:
        con = connect(db, a.tz)
        try:
            s = import_folder(con, src)
        finally:
            con.close()  # release the DB file between runs so other tools can open it
        print(f"done: {s['imported']} imported ({s['rows']} rows), {s['skipped']} skipped, {s['failed']} failed")
        if not a.watch:
            return 0
        time.sleep(a.watch)


if __name__ == "__main__":
    sys.exit(main())

import gzip
import json
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fitair_import as fi  # noqa: E402

# 2026-03-10 00:00 UTC
D = 1773100800000
H = 3_600_000


def write(folder: Path, typ: str, day: str, recs: list[dict]):
    p = folder / f"{typ}_{day}.jsonl.gz"
    with gzip.open(p, "wt") as f:
        for r in recs:
            f.write(json.dumps(r) + "\n")
    return p


@pytest.fixture
def env(tmp_path):
    src = tmp_path / "FitAir"
    src.mkdir()
    write(src, "heart_rate", "2026-03-10", [{"t": D + i * 60000, "bpm": 60 + i, "origin": "x"} for i in range(10)])
    write(src, "steps", "2026-03-10", [{"start_ms": D + H, "end_ms": D + 2 * H, "count": 500, "origin": "x"},
                                        {"start_ms": D + 3 * H, "end_ms": D + 4 * H, "count": 700, "origin": "x"}])
    write(src, "resting_hr", "2026-03-10", [{"t": D + 8 * H, "bpm": 52, "origin": "x"}])
    write(src, "hrv", "2026-03-10", [{"t": D + 2 * H, "rmssd": 41.5, "origin": "x"}])
    write(src, "sleep", "2026-03-10", [{"start_ms": D + 22 * H, "end_ms": D + 30 * H, "origin": "x",
                                         "stages": [{"start_ms": D + 22 * H, "end_ms": D + 29 * H, "stage": 5},
                                                    {"start_ms": D + 29 * H, "end_ms": D + 30 * H, "stage": 1}]}])
    write(src, "exercise", "2026-03-10", [{"start_ms": D + 5 * H, "end_ms": D + 6 * H, "type": 56, "title": "Run", "origin": "x"}])
    (src / "manifest.json").write_text("{}")
    (src / "notes.txt").write_text("ignored")
    con = fi.connect(str(tmp_path / "t.duckdb"))
    yield src, con
    con.close()


def count(con, t):
    return con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]


def test_import_and_skip(env):
    src, con = env
    s = fi.import_folder(con, src, log=lambda *a, **k: None)
    assert s["imported"] == 6 and s["failed"] == 0
    assert count(con, "heart_rate") == 10 and count(con, "sleep_stage") == 2
    s2 = fi.import_folder(con, src, log=lambda *a, **k: None)
    assert s2["imported"] == 0 and s2["skipped"] == 6


def test_changed_file_replaces_day(env):
    src, con = env
    fi.import_folder(con, src, log=lambda *a, **k: None)
    # a different day stays untouched
    write(src, "heart_rate", "2026-03-11", [{"t": D + 24 * H, "bpm": 70, "origin": "x"}])
    p = write(src, "heart_rate", "2026-03-10", [{"t": D + 60000, "bpm": 99, "origin": "x"}])
    os.utime(p, (p.stat().st_atime, p.stat().st_mtime + 10))
    s = fi.import_folder(con, src, log=lambda *a, **k: None)
    assert s["imported"] == 2
    assert count(con, "heart_rate") == 2
    assert con.execute("SELECT bpm FROM heart_rate WHERE t=?", [D + 60000]).fetchone()[0] == 99


def test_corrupt_gzip_skipped(env, capsys):
    src, con = env
    good = write(src, "hrv", "2026-03-11", [{"t": D + 25 * H, "rmssd": 30, "origin": "x"}])
    data = good.read_bytes()
    (src / "hrv_2026-03-12.jsonl.gz").write_bytes(data[: len(data) // 2])  # partial sync
    (src / "steps_2026-03-12.jsonl.gz").write_bytes(b"not gzip")
    s = fi.import_folder(con, src, log=lambda *a, **k: None)
    assert s["failed"] == 2 and s["imported"] == 7
    assert "WARNING" in capsys.readouterr().err


def test_views_and_daily_summary(env):
    src, con = env
    fi.import_folder(con, src, log=lambda *a, **k: None)
    t = con.execute("SELECT t_utc FROM hrv_v").fetchone()[0]
    assert str(t) == "2026-03-10 02:00:00"
    row = con.execute("SELECT day, steps, resting_hr, mean_hr, sleep_hours FROM daily_summary WHERE steps IS NOT NULL").fetchone()
    assert str(row[0]) == "2026-03-10" and row[1] == 1200 and row[2] == 52
    assert abs(row[3] - 64.5) < 1e-9
    # sleep ends 06:00 UTC on 03-11 -> local day 03-11; 8h in bed minus 1h awake
    sl = con.execute("SELECT day, sleep_hours FROM daily_summary WHERE sleep_hours IS NOT NULL").fetchone()
    assert str(sl[0]) == "2026-03-11" and abs(sl[1] - 7.0) < 1e-9


def test_cli(env, tmp_path):
    src, con = env
    con.close()
    db = tmp_path / "cli.duckdb"
    assert fi.main(["--src", str(src), "--db", str(db), "--tz", "UTC"]) == 0
    assert fi.main(["--src", str(src), "--db", str(db), "--tz", "UTC"]) == 0
    c = fi.connect(str(db))
    assert count(c, "import_log") == 6
    c.close()
    # fixture teardown closes con again; reopen-safe
    env_con = fi.connect(str(tmp_path / "t.duckdb"))
    env_con.close()

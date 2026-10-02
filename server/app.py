"""FitAir laptop server: FastAPI + DuckDB. See API.md. Usage: python app.py serve | export [--out DIR]"""
from __future__ import annotations

import datetime as dt
import hmac
import os
import secrets
import sys
import threading
from pathlib import Path

import duckdb
from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel

import analytics as an

HERE = Path(__file__).resolve().parent
KIND = {"i": "BIGINT", "f": "DOUBLE", "s": "VARCHAR"}
JSON_KEY = {"start_ms": "start", "end_ms": "end", "n": "count"}  # column -> API field
# type -> (columns, natural key, watermark column). Every table also has `origin`.
SPEC = {
    "heart_rate": ("t:i bpm:f", "t", "t"),
    "steps": ("start_ms:i end_ms:i n:i", "start_ms end_ms", "end_ms"),
    "distance": ("start_ms:i end_ms:i meters:f", "start_ms end_ms", "end_ms"),
    "total_calories": ("start_ms:i end_ms:i kcal:f", "start_ms end_ms", "end_ms"),
    "resting_hr": ("t:i bpm:f", "t", "t"),
    "hrv": ("t:i rmssd:f", "t", "t"),
    "respiratory_rate": ("t:i rate:f", "t", "t"),
    "sleep": ("start_ms:i end_ms:i", "start_ms", "end_ms"),
    "exercise": ("start_ms:i end_ms:i type:i title:s", "start_ms end_ms", "end_ms"),
}
OPTIONAL = {"origin": "unknown", "title": ""}


class Ingest(BaseModel):
    type: str
    records: list[dict]


def _cols(t):
    return [c.split(":") for c in SPEC[t][0].split()] + [["origin", "s"]]


def open_db(path: str) -> duckdb.DuckDBPyConnection:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect(path)
    for t, (_, key, _) in SPEC.items():
        cols = ", ".join(f"{c} {KIND[k]}" for c, k in _cols(t))
        pk = ", ".join(key.split() + ["origin"])
        con.execute(f"CREATE TABLE IF NOT EXISTS {t} ({cols}, PRIMARY KEY ({pk}))")
    con.execute("CREATE TABLE IF NOT EXISTS sleep_stage (sleep_start BIGINT, origin VARCHAR, "
                "start_ms BIGINT, end_ms BIGINT, stage INTEGER)")
    return con


def load_api_key() -> str:
    if os.environ.get("FITAIR_API_KEY"):
        return os.environ["FITAIR_API_KEY"]
    f = HERE / ".apikey"
    if not f.exists():
        f.write_text(secrets.token_urlsafe(32) + "\n")
        f.chmod(0o600)
        print(f"Created API key file {f}\nAPI key: {f.read_text().strip()}", flush=True)
    return f.read_text().strip()


def _coerce(rec: dict, col: str, kind: str):
    key = JSON_KEY.get(col, col)
    if key not in rec:
        if col in OPTIONAL:
            return OPTIONAL[col]
        raise ValueError(f"missing field {key!r}")
    v = rec[key]
    return int(round(float(v))) if kind == "i" else float(v) if kind == "f" else str(v)


def _bulk_upsert(con, typ, cols, rows):
    """One statement per batch (executemany is ~100x slower). Last duplicate key in a batch wins."""
    names = [c for c, _ in cols]
    pkidx = [names.index(c) for c in SPEC[typ][1].split() + ["origin"]]
    uniq = {tuple(r[i] for i in pkidx): r for r in rows}
    if not uniq:
        return
    sel = ", ".join(f"unnest(?::{KIND[k]}[]) AS {c}" for c, k in cols)
    con.execute(f"INSERT OR REPLACE INTO {typ} ({', '.join(names)}) SELECT * FROM (SELECT {sel})",
                [list(col) for col in zip(*uniq.values())])


def ingest(con, typ: str, records: list[dict]):
    cols = _cols(typ)
    try:
        rows = [[_coerce(r, c, k) for c, k in cols] for r in records]
        stages = []
        if typ == "sleep":
            for r, row in zip(records, rows):
                for s in r.get("stages") or []:
                    stages.append([row[0], row[2], int(s["start"]), int(s["end"]), int(s["stage"])])
    except (ValueError, TypeError, KeyError, AttributeError) as e:
        raise HTTPException(422, f"bad {typ} record: {e}")
    con.execute("BEGIN")
    try:
        _bulk_upsert(con, typ, cols, rows)
        if typ == "sleep":  # stages are replaced wholesale with their session
            con.executemany("DELETE FROM sleep_stage WHERE sleep_start=? AND origin=?",
                            [[r[0], r[2]] for r in rows])
            if stages:
                con.execute("INSERT INTO sleep_stage SELECT * FROM (SELECT unnest(?::BIGINT[]), unnest(?::VARCHAR[]), "
                            "unnest(?::BIGINT[]), unnest(?::BIGINT[]), unnest(?::INTEGER[]))",
                            [list(c) for c in zip(*stages)])
        con.execute("COMMIT")
    except Exception:
        con.execute("ROLLBACK")
        raise


def export_parquet(con, out: Path) -> list[str]:
    out.mkdir(parents=True, exist_ok=True)
    tables = list(SPEC) + ["sleep_stage"]
    for t in tables:
        con.execute(f"COPY {t} TO '{out / (t + '.parquet')}' (FORMAT PARQUET)")
    return tables


def create_app(db_path: str | None = None, api_key: str | None = None) -> FastAPI:
    con = open_db(db_path or os.environ.get("FITAIR_DB", str(HERE / "data" / "fitair.duckdb")))
    key = api_key or load_api_key()
    lock = threading.Lock()  # one shared connection; DuckDB queries here are short
    default_tz = os.environ.get("FITAIR_TZ", "Europe/Stockholm")
    maxhr = lambda: float(os.environ.get("FITAIR_MAXHR", 190))

    def auth(x_api_key: str = Header(default="")):
        if not hmac.compare_digest(x_api_key.encode(), key.encode()):
            raise HTTPException(401, "invalid or missing X-Api-Key")

    def zone(name):
        try:
            return an.get_tz(name)
        except ValueError as e:
            raise HTTPException(400, str(e))

    app = FastAPI(title="FitAir", dependencies=[Depends(auth)])

    @app.post("/ingest")
    def post_ingest(body: Ingest):
        if body.type not in SPEC:
            raise HTTPException(400, f"unknown type {body.type!r}; expected one of {sorted(SPEC)}")
        with lock:
            ingest(con, body.type, body.records)
        return {"ok": True, "received": len(body.records)}

    @app.get("/health")
    def health():
        with lock:
            return {"ok": True, "counts": {t: con.execute(f"SELECT count(*) FROM {t}").fetchone()[0] for t in SPEC}}

    @app.get("/watermark")
    def watermark(type: str):
        if type not in SPEC:
            raise HTTPException(400, f"unknown type {type!r}")
        with lock:
            return {"type": type, "maxT": con.execute(f"SELECT max({SPEC[type][2]}) FROM {type}").fetchone()[0]}

    @app.get("/summary/day")
    def summary_day(date: dt.date, tz: str = default_tz):
        z = zone(tz)
        with lock:
            ser = an.series(con, z, date - dt.timedelta(days=28), date, maxhr())
            day = an.public(ser[date.isoformat()])
            day["readiness"] = an.readiness(con, z, date, maxhr(), ser)["score"]
        return day

    @app.get("/summary/range")
    def summary_range(to: dt.date, tz: str = default_tz, from_: dt.date = Query(alias="from")):
        z = zone(tz)
        if to < from_ or (to - from_).days > 366:
            raise HTTPException(400, "range must be 0..366 days and from <= to")
        with lock:
            ser = an.series(con, z, from_ - dt.timedelta(days=28), to, maxhr())
            days = []
            for d in an._days(from_, to):
                day = an.public(ser[d.isoformat()])
                day["readiness"] = an.readiness(con, z, d, maxhr(), ser)["score"]
                days.append(day)
        return {"days": days}

    @app.get("/hr")
    def hr(from_ms: int, to_ms: int, bucket_s: int = 60):
        with lock:
            if bucket_s <= 0:
                rows = con.execute("SELECT t, bpm, bpm, bpm, 1 FROM heart_rate WHERE t>=? AND t<? ORDER BY t",
                                   [from_ms, to_ms]).fetchall()
            else:
                b = bucket_s * 1000
                rows = con.execute(f"SELECT (t//{b})*{b}, min(bpm), avg(bpm), max(bpm), count(*) FROM heart_rate "
                                   "WHERE t>=? AND t<? GROUP BY 1 ORDER BY 1", [from_ms, to_ms]).fetchall()
        return {"bucket_s": max(bucket_s, 0),
                "points": [dict(t=t, min=mn, mean=round(m, 1), max=mx, n=n) for t, mn, m, mx, n in rows]}

    @app.get("/workouts")
    def get_workouts(to: dt.date, tz: str = default_tz, from_: dt.date = Query(alias="from")):
        z = zone(tz)
        with lock:
            return {"workouts": an.workouts(con, z, from_, to, maxhr())}

    @app.get("/readiness")
    def get_readiness(date: dt.date, tz: str = default_tz):
        z = zone(tz)
        with lock:
            return an.readiness(con, z, date, maxhr())

    @app.get("/baselines")
    def get_baselines(tz: str = default_tz):
        z = zone(tz)
        with lock:
            return an.baselines(con, z, dt.datetime.now(z).date(), maxhr())

    app.state.con = con
    return app


def main(argv: list[str]):
    cmd = argv[0] if argv else "serve"
    if cmd == "serve":
        import uvicorn
        uvicorn.run(create_app(), host=os.environ.get("FITAIR_HOST", "0.0.0.0"),
                    port=int(os.environ.get("FITAIR_PORT", 8787)))
    elif cmd == "export":  # stop the server first: DuckDB allows one process on the file
        out = Path(argv[argv.index("--out") + 1]) if "--out" in argv else HERE / "data" / "parquet"
        db = os.environ.get("FITAIR_DB", str(HERE / "data" / "fitair.duckdb"))
        print("Exported", ", ".join(export_parquet(open_db(db), out)), "to", out)
    else:
        sys.exit("usage: python app.py [serve | export [--out DIR]]")


if __name__ == "__main__":
    main(sys.argv[1:])

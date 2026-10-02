"""Day summaries, workout metrics and readiness. All SQL runs on a DuckDB connection."""
from __future__ import annotations

import datetime as dt
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

STAGE_NAMES = {0: "unknown", 1: "awake", 2: "sleeping", 3: "out_of_bed",
               4: "light", 5: "deep", 6: "rem", 7: "awake_in_bed"}
ASLEEP = {2, 4, 5, 6}
WEIGHTS = {"sleep": 0.35, "hrv": 0.30, "resting_hr": 0.20, "load": 0.15}
MIN_BASELINE_DAYS = 7
DEFAULT_INTENSITY = 0.6  # used for load when a workout has no HR samples


def get_tz(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError, KeyError):
        raise ValueError(f"unknown timezone {name!r}")


def day_bounds(d: dt.date, tz: ZoneInfo) -> tuple[int, int]:
    lo = dt.datetime.combine(d, dt.time.min, tzinfo=tz)
    hi = dt.datetime.combine(d + dt.timedelta(days=1), dt.time.min, tzinfo=tz)
    return int(lo.timestamp() * 1000), int(hi.timestamp() * 1000)


def _mean(v):
    return sum(v) / len(v) if v else None


def _r(x, n=1):
    return None if x is None else round(x, n)


def _days(d0: dt.date, d1: dt.date) -> list[dt.date]:
    return [d0 + dt.timedelta(i) for i in range((d1 - d0).days + 1)]


def _query(con, tz, dates, sql, ctes=""):
    """Run `sql` with a `days(d, lo, hi)` table of local-day bounds (DST-correct) in scope."""
    rows = ",".join(f"('{d.isoformat()}',{lo},{hi})" for d in dates for lo, hi in [day_bounds(d, tz)])
    return con.execute(f"WITH days(d,lo,hi) AS (VALUES {rows}){',' + ctes if ctes else ''} {sql}").fetchall()


def exercises(con, lo: int, hi: int) -> list[dict]:
    """Sessions starting in [lo, hi); overlapping duplicates (same workout from two apps) dropped."""
    rows = con.execute(
        """SELECT e.start_ms, e.end_ms, e.type, e.title, e.origin,
                  (SELECT avg(bpm) FROM heart_rate h WHERE h.t BETWEEN e.start_ms AND e.end_ms)
           FROM exercise e WHERE e.start_ms >= ? AND e.start_ms < ? ORDER BY e.start_ms, e.end_ms DESC""",
        [lo, hi]).fetchall()
    out, last_end = [], -1
    for s, e, typ, title, origin, avg in rows:
        if s < last_end:
            continue
        last_end = e
        out.append(dict(start=s, end=e, type=typ, title=title, origin=origin, hr_avg=avg))
    return out


def series(con, tz: ZoneInfo, d0: dt.date, d1: dt.date, maxhr: float) -> dict[str, dict]:
    """Per-local-day metrics for d0..d1, keyed by ISO date. Keys starting with '_' are internal."""
    dates = _days(d0, d1)
    lo0, hi1 = day_bounds(d0, tz)[0], day_bounds(d1, tz)[1]
    q = lambda sql, ctes="": _query(con, tz, dates, sql, ctes)
    out = {d.isoformat(): dict(
        date=d.isoformat(), steps=0, distance_m=0.0, active_minutes_est=0, resting_hr=None,
        hrv_rmssd_night_mean=None, hr_mean=None, hr_min=None, hr_max=None, sleep_minutes=None,
        sleep_stages_minutes={}, exercise_count=0, kcal_total=0.0, readiness=None, _load=0.0)
        for d in dates}

    for d, mean, mn, mx in q("SELECT d, avg(bpm), min(bpm), max(bpm) FROM heart_rate "
                             "JOIN days ON t>=lo AND t<hi GROUP BY d"):
        out[d].update(hr_mean=_r(mean), hr_min=_r(mn), hr_max=_r(mx))
    for d, mean in q("SELECT d, avg(bpm) FROM resting_hr JOIN days ON t>=lo AND t<hi GROUP BY d"):
        out[d]["resting_hr"] = _r(mean)

    # Interval metrics: per minute take the origin with the larger value (no summing across origins).
    def dedup(table, col):
        return q("SELECT d, sum(v), count(*) FILTER (WHERE v>=60) FROM b JOIN days "
                 "ON mn*60000>=lo AND mn*60000<hi GROUP BY d",
                 f"m AS (SELECT origin, start_ms//60000 AS mn, sum({col}) AS v FROM {table} "
                 f"WHERE start_ms>={lo0} AND start_ms<{hi1} GROUP BY 1,2), "
                 "b AS (SELECT mn, max(v) AS v FROM m GROUP BY mn)")
    for d, v, active in dedup("steps", "n"):
        out[d].update(steps=int(v), active_minutes_est=active)  # active = minutes with >=60 steps
    for d, v, _ in dedup("distance", "meters"):
        out[d]["distance_m"] = _r(v)
    for d, v, _ in dedup("total_calories", "kcal"):
        out[d]["kcal_total"] = _r(v)

    # Sleep belongs to the day it ends on. If several origins recorded it, use the fuller one.
    join = "FROM sleep s JOIN days ON s.end_ms>=lo AND s.end_ms<hi"
    stages: dict = {}
    for d, st, org, stage, mins in q(f"SELECT d, s.start_ms, s.origin, g.stage, "
                                     f"sum(g.end_ms-g.start_ms)/60000.0 {join} JOIN sleep_stage g "
                                     "ON g.sleep_start=s.start_ms AND g.origin=s.origin GROUP BY ALL"):
        stages.setdefault((st, org), {})[stage] = mins
    hrv = {(st, org): (sm, n) for st, org, sm, n in q(
        f"SELECT s.start_ms, s.origin, sum(h.rmssd), count(*) {join} JOIN hrv h "
        "ON h.t>=s.start_ms AND h.t<=s.end_ms GROUP BY ALL")}
    per: dict = {}  # (date, origin) -> [asleep_min, {stage: min}, hrv_sum, hrv_n]
    for d, st, en, org in q(f"SELECT d, s.start_ms, s.end_ms, s.origin {join}"):
        stg = stages.get((st, org), {})
        asleep = sum(m for k, m in stg.items() if k in ASLEEP) if stg else (en - st) / 60000.0
        a = per.setdefault((d, org), [0.0, {}, 0.0, 0])
        a[0] += asleep
        for k, m in stg.items():
            a[1][STAGE_NAMES.get(k, str(k))] = a[1].get(STAGE_NAMES.get(k, str(k)), 0) + m
        sm, n = hrv.get((st, org), (0.0, 0))
        a[2] += sm
        a[3] += n
    for (d, _), (asleep, stg, sm, n) in sorted(per.items(), key=lambda kv: kv[1][0]):  # fullest last
        out[d].update(sleep_minutes=_r(asleep), sleep_stages_minutes={k: _r(v) for k, v in stg.items()},
                      hrv_rmssd_night_mean=_r(sm / n) if n else None)

    for ex in exercises(con, lo0, hi1):
        d = dt.datetime.fromtimestamp(ex["start"] / 1000, tz).date().isoformat()
        mins = (ex["end"] - ex["start"]) / 60000.0
        intensity = ex["hr_avg"] / maxhr if ex["hr_avg"] else DEFAULT_INTENSITY
        out[d]["exercise_count"] += 1
        out[d]["_load"] += mins * intensity
    return out


def public(day: dict) -> dict:
    return {k: v for k, v in day.items() if not k.startswith("_")}


# ---------- baselines & readiness ----------

def _stats(vals):
    n = len(vals)
    if not n:
        return None, None
    m = sum(vals) / n
    sd = (sum((x - m) ** 2 for x in vals) / (n - 1)) ** 0.5 if n > 1 else 0.0
    return m, sd


def baselines_from(days: list[dict], maxhr: float) -> dict:
    b = {"window_days": len(days), "max_hr": maxhr}
    for name, key in (("resting_hr_28d", "resting_hr"), ("hrv_28d", "hrv_rmssd_night_mean"),
                      ("sleep_28d", "sleep_minutes")):
        vals = [x[key] for x in days if x[key] is not None]
        m, sd = _stats(vals)
        suffix = "_min" if key == "sleep_minutes" else ""
        b[f"{name}_mean{suffix}"], b[f"{name}_sd{suffix}"], b[f"{name}_days"] = _r(m, 2), _r(sd, 2), len(vals)
    first = next((i for i, x in enumerate(days)
                  if x["steps"] or x["hr_mean"] is not None or x["sleep_minutes"] is not None), None)
    span = 0 if first is None else len(days) - first  # days since data started
    b["data_days"] = span
    total = sum(x["_load"] for x in days)
    b["weekly_load_28d_mean"] = _r(total / (max(span, 7) / 7), 1)
    return b


def baselines(con, tz, today: dt.date, maxhr: float) -> dict:
    ser = series(con, tz, today - dt.timedelta(days=27), today, maxhr)
    return baselines_from(list(ser.values()), maxhr)


def _clip(x):
    return max(0.0, min(100.0, x))


def readiness(con, tz, d: dt.date, maxhr: float, ser: dict | None = None) -> dict:
    if ser is None:
        ser = series(con, tz, d - dt.timedelta(days=28), d, maxhr)
    today = ser[d.isoformat()]
    base_days = [ser[(d - dt.timedelta(days=i)).isoformat()] for i in range(28, 0, -1)]
    b = baselines_from(base_days, maxhr)
    comps, notes, text = {}, [], {}

    def skip(label, why):
        notes.append(f"{label} skipped: {why}.")

    # sleep: last night's duration relative to the 28-day mean
    x = today["sleep_minutes"]
    if x is None:
        skip("Sleep", "no sleep session ends on this date")
    elif b["sleep_28d_days"] < MIN_BASELINE_DAYS:
        skip("Sleep", f"only {b['sleep_28d_days']} baseline days (<{MIN_BASELINE_DAYS})")
    else:
        ratio = x / b["sleep_28d_mean_min"]
        comps["sleep"] = dict(score=_r(_clip(100 - 200 * max(0.0, 1 - ratio))), value_min=x, ratio=_r(ratio, 2))
        text["sleep"] = f"Sleep {x / 60:.1f} h vs 28-day mean {b['sleep_28d_mean_min'] / 60:.1f} h"

    # hrv / resting hr: z-score vs baseline (sd floored so tiny variance cannot explode the score)
    for name, key, unit, sign, floor in (("hrv", "hrv_rmssd_night_mean", "ms", +1, 0.05),
                                         ("resting_hr", "resting_hr", "bpm", -1, 0.02)):
        pre = "hrv_28d" if name == "hrv" else "resting_hr_28d"
        x, label = today[key], "Overnight HRV" if name == "hrv" else "Resting HR"
        if x is None:
            skip(label, "no reading for this date")
        elif b[f"{pre}_days"] < MIN_BASELINE_DAYS:
            skip(label, f"only {b[f'{pre}_days']} baseline days (<{MIN_BASELINE_DAYS})")
        else:
            m, sd = b[f"{pre}_mean"], b[f"{pre}_sd"]
            z = (x - m) / max(sd, 1.0, floor * m)
            comps[name] = dict(score=_r(_clip(75 + 25 * sign * z)), value=x, z=_r(z, 2))
            text[name] = f"{label} {x:.0f} {unit} vs mean {m:.0f} {unit} (z={z:+.1f}{', lower is better' if sign < 0 else ''})"

    # load: last 7 days vs the 28-day weekly average; spikes above ~1.1x cost points
    if b["data_days"] < MIN_BASELINE_DAYS:
        skip("Training load", f"only {b['data_days']} baseline days (<{MIN_BASELINE_DAYS})")
    elif b["weekly_load_28d_mean"] <= 0:
        skip("Training load", "no training history")
    else:
        acute = sum(x["_load"] for x in base_days[-7:])
        ratio = acute / b["weekly_load_28d_mean"]
        comps["load"] = dict(score=_r(_clip(100 - max(0.0, ratio - 1.1) * 100)), acute_7d=_r(acute, 1), ratio=_r(ratio, 2))
        text["load"] = f"7-day load {acute:.0f} vs weekly average {b['weekly_load_28d_mean']:.0f} (ratio {ratio:.2f})"

    score = None
    if len(comps) < 2:
        notes.insert(0, f"Not enough data for a readiness score (needs >= {MIN_BASELINE_DAYS} days of baseline "
                        "and at least two signals).")
    else:
        wsum = sum(WEIGHTS[k] for k in comps)
        score = round(sum(WEIGHTS[k] * c["score"] for k, c in comps.items()) / wsum)
        impact = {k: WEIGHTS[k] * (c["score"] - 75) / wsum for k, c in comps.items()}  # vs a typical day (75)
        drivers = [("Helping" if impact[k] >= 0 else "Hurting") + ": " + text[k]
                   for k in sorted(impact, key=lambda k: -abs(impact[k]))[:3]]
        notes = drivers + notes
    return dict(date=d.isoformat(), score=score,
                components={k: comps.get(k) for k in WEIGHTS}, baseline=b, notes=notes)


# ---------- workouts ----------

def workout_metrics(hr: list[tuple[int, float]], start: int, end: int, maxhr: float) -> dict:
    """hr: (t_ms, bpm) sorted, covering at least [start, end + 75 s]."""
    inside = [(t, b) for t, b in hr if start <= t <= end]
    zones = {f"z{i}": 0.0 for i in range(1, 6)}
    res = dict(hr_mean=None, hr_max=None, hr_recovery_1min=None, drift_pct=None)
    if not inside:
        return {**res, "minutes_in_zone": zones}
    win = lambda a, z: [b for t, b in hr if a <= t <= z]
    res.update(hr_mean=_r(_mean([b for _, b in inside])), hr_max=_r(max(b for _, b in inside)))

    at_end, after = win(end - 15000, end), win(end + 45000, end + 75000)
    if at_end and after:
        res["hr_recovery_1min"] = _r(_mean(at_end) - _mean(after))

    dur = end - start
    if dur >= 10 * 60000:  # skip 10% warm-up, compare second half with first half
        t0 = start + 0.1 * dur
        mid = (t0 + end) / 2
        a = [b for t, b in inside if t0 <= t < mid]
        c = [b for t, b in inside if t >= mid]
        if a and c:
            res["drift_pct"] = _r((_mean(c) - _mean(a)) / _mean(a) * 100, 2)

    for i, (t, b) in enumerate(inside):  # each sample covers the time until the next (capped at 2 min)
        nxt = inside[i + 1][0] if i + 1 < len(inside) else end
        frac = b / maxhr
        if frac >= 0.5:
            zones[f"z{min(int((frac - 0.5) / 0.1), 4) + 1}"] += min(max(nxt - t, 0), 120000) / 60000
    return {**res, "minutes_in_zone": {k: _r(v) for k, v in zones.items()}}


def workouts(con, tz, d0: dt.date, d1: dt.date, maxhr: float) -> list[dict]:
    out = []
    for ex in exercises(con, day_bounds(d0, tz)[0], day_bounds(d1, tz)[1]):
        hr = con.execute("SELECT t, bpm FROM heart_rate WHERE t BETWEEN ? AND ? ORDER BY t",
                         [ex["start"], ex["end"] + 75000]).fetchall()
        out.append(dict(start=ex["start"], end=ex["end"], type=ex["type"], title=ex["title"],
                        duration_min=_r((ex["end"] - ex["start"]) / 60000.0),
                        **workout_metrics(hr, ex["start"], ex["end"], maxhr)))
    return out

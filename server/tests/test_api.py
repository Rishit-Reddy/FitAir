from conftest import DAY, KEY, MIN, T0, day_str, load_history


def test_auth(raw_client):
    assert raw_client.get("/health").status_code == 401
    assert raw_client.get("/health", headers={"X-Api-Key": "nope"}).status_code == 401
    assert raw_client.get("/health", headers={"X-Api-Key": KEY}).status_code == 200


def test_ingest_idempotent_and_watermark(api):
    recs = [{"t": T0 + i * 1000, "bpm": 60 + i, "origin": "a"} for i in range(5)]
    assert api.post("heart_rate", recs) == {"ok": True, "received": 5}
    api.post("heart_rate", recs)
    api.post("heart_rate", [{"t": T0, "bpm": 99, "origin": "a"}])  # same key -> overwrite
    assert api.get("/health")["counts"]["heart_rate"] == 5
    assert api.get("/hr", from_ms=T0, to_ms=T0 + 1, bucket_s=0)["points"][0]["mean"] == 99
    assert api.get("/watermark", type="heart_rate") == {"type": "heart_rate", "maxT": T0 + 4000}
    assert api.get("/watermark", type="sleep")["maxT"] is None


def test_ingest_validation(raw_client):
    h = {"X-Api-Key": KEY}
    assert raw_client.post("/ingest", json={"type": "nope", "records": []}, headers=h).status_code == 400
    assert raw_client.post("/ingest", json={"type": "heart_rate", "records": [{"t": 1}]}, headers=h).status_code == 422


def test_sleep_stages_replaced(api):
    s = {"start": T0, "end": T0 + 8 * 60 * MIN, "origin": "w",
         "stages": [{"start": T0, "end": T0 + 60 * MIN, "stage": 5}, {"start": T0 + 60 * MIN, "end": T0 + 120 * MIN, "stage": 1}]}
    api.post("sleep", [s])
    s["stages"] = [{"start": T0, "end": T0 + 120 * MIN, "stage": 4}]
    api.post("sleep", [s])
    day = api.get("/summary/day", date=day_str(0))
    assert day["sleep_stages_minutes"] == {"light": 120.0}
    assert day["sleep_minutes"] == 120.0


def test_steps_dedup_across_origins(api):
    m = T0 + 600 * MIN
    api.post("steps", [
        {"start": m, "end": m + MIN, "count": 80, "origin": "phone"},
        {"start": m, "end": m + MIN, "count": 100, "origin": "watch"},
        {"start": m + MIN, "end": m + 2 * MIN, "count": 30, "origin": "phone"}])
    day = api.get("/summary/day", date=day_str(0))
    assert day["steps"] == 130 and day["active_minutes_est"] == 1


def test_summary_day_and_range(api):
    load_history(api, 10)
    d = api.get("/summary/day", date=day_str(5))
    assert d["steps"] == 6000 and d["sleep_minutes"] == 480
    assert d["hrv_rmssd_night_mean"] and 55 < d["hrv_rmssd_night_mean"] < 65
    assert d["resting_hr"] and d["hr_min"] >= 60 and d["readiness"] is None  # only 5 baseline days -> null
    r = api.get("/summary/range", **{"from": day_str(0), "to": day_str(4)})
    assert [x["date"] for x in r["days"]] == [day_str(i) for i in range(5)]


def test_hr_buckets(api):
    api.post("heart_rate", [{"t": T0 + s * 1000, "bpm": b, "origin": "a"} for s, b in ((0, 60), (30, 80), (70, 100))])
    pts = api.get("/hr", from_ms=T0, to_ms=T0 + DAY, bucket_s=60)["points"]
    assert pts == [{"t": T0, "min": 60, "mean": 70.0, "max": 80, "n": 2},
                   {"t": T0 + 60_000, "min": 100, "mean": 100.0, "max": 100, "n": 1}]


def test_workout_metrics(api):
    s = T0 + 10 * 3600_000
    e = s + 40 * MIN
    hr = [{"t": t, "bpm": 150 if t < s + 22 * MIN else 160, "origin": "w"} for t in range(s, e, 10_000)]
    hr += [{"t": e + k * 1000, "bpm": 130, "origin": "w"} for k in range(1, 91)]
    api.post("heart_rate", hr)
    api.post("exercise", [{"start": s, "end": e, "type": 56, "title": "Run", "origin": "w"}])
    w = api.get("/workouts", **{"from": day_str(0), "to": day_str(0)})["workouts"]
    assert len(w) == 1 and w[0]["duration_min"] == 40
    w = w[0]
    assert w["hr_max"] == 160 and w["hr_mean"] == round((150 * 22 + 160 * 18) / 40, 1)
    assert w["hr_recovery_1min"] == 30
    assert abs(w["drift_pct"] - 6.67) < 0.01
    assert w["minutes_in_zone"] == {"z1": 0, "z2": 0, "z3": 22, "z4": 18, "z5": 0}


def test_maxhr_env(api, monkeypatch):
    monkeypatch.setenv("FITAIR_MAXHR", "160")
    s, e = T0 + 3600_000, T0 + 3600_000 + 12 * MIN
    api.post("heart_rate", [{"t": t, "bpm": 150, "origin": "w"} for t in range(s, e, 10_000)])
    api.post("exercise", [{"start": s, "end": e, "type": 1, "title": "", "origin": "w"}])
    z = api.get("/workouts", **{"from": day_str(0), "to": day_str(0)})["workouts"][0]["minutes_in_zone"]
    assert z["z5"] == 12  # 150/160 = 94%


def test_readiness_sparse_is_null(api):
    load_history(api, 4)
    r = api.get("/readiness", date=day_str(3))
    assert r["score"] is None and any("Not enough data" in n for n in r["notes"])


def test_readiness_normal_vs_bad_night(api):
    load_history(api, 35, bad_last=True)
    good = api.get("/readiness", date=day_str(33))
    bad = api.get("/readiness", date=day_str(34))
    assert good["score"] >= 75 and set(good["components"]) == {"sleep", "hrv", "resting_hr", "load"}
    assert bad["score"] < good["score"] - 25
    assert bad["components"]["hrv"]["z"] < -3 and bad["components"]["sleep"]["score"] < 50
    assert bad["notes"][0].startswith("Hurting")
    assert 0 <= bad["score"] <= 100 and bad["baseline"]["hrv_28d_days"] == 28
    assert api.get("/summary/day", date=day_str(34))["readiness"] == bad["score"]


def test_baselines(api):
    load_history(api, 3)
    b = api.get("/baselines")  # "today" is real time -> no data in window
    assert b["sleep_28d_days"] == 0 and b["sleep_28d_mean_min"] is None


def test_bad_tz_and_export(api, tmp_path):
    r = api.c.get("/baselines", params={"tz": "Mars/Base"}, headers={"X-Api-Key": KEY})
    assert r.status_code == 400
    import app as server
    api.post("heart_rate", [{"t": T0, "bpm": 60, "origin": "a"}])
    server.export_parquet(api.c.app.state.con, tmp_path / "pq")
    assert (tmp_path / "pq" / "heart_rate.parquet").exists()

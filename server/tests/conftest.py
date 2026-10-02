import random
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app as server  # noqa: E402

KEY = "test-key"
DAY = 86_400_000
T0 = 1_767_225_600_000  # 2026-01-01T00:00:00Z; tests use tz=UTC
MIN = 60_000


def day_str(i):
    import datetime as dt
    return (dt.date(2026, 1, 1) + dt.timedelta(days=i)).isoformat()


class Api:
    def __init__(self, c):
        self.c = c

    def post(self, typ, records):
        r = self.c.post("/ingest", json={"type": typ, "records": records}, headers={"X-Api-Key": KEY})
        assert r.status_code == 200, r.text
        return r.json()

    def get(self, path, **params):
        params.setdefault("tz", "UTC")
        r = self.c.get(path, params=params, headers={"X-Api-Key": KEY})
        assert r.status_code == 200, r.text
        return r.json()


@pytest.fixture
def api(tmp_path):
    return Api(TestClient(server.create_app(str(tmp_path / "t.duckdb"), KEY)))


@pytest.fixture
def raw_client(tmp_path):
    return TestClient(server.create_app(str(tmp_path / "t.duckdb"), KEY))


def load_history(api, days=35, bad_last=False):
    """Synthetic history: nightly sleep + HRV, resting HR, daytime HR, steps, a run every 3rd day."""
    rnd = random.Random(1)
    hr, hrv, rhr, sleep, steps, ex = [], [], [], [], [], []
    for i in range(days):
        base = T0 + i * DAY
        bad = bad_last and i == days - 1
        hours = 4.5 if bad else 8
        s = base - 60 * MIN  # 23:00 previous day
        e = s + int(hours * 60) * MIN
        stages = [{"start": s + h * 60 * MIN, "end": s + (h + 1) * 60 * MIN, "stage": [4, 5, 4, 6][h % 4]}
                  for h in range(int(hours))]
        sleep.append({"start": s, "end": e, "origin": "watch", "stages": stages})
        for t in range(s, e, 30 * MIN):
            hrv.append({"t": t, "rmssd": (30 if bad else 60) + rnd.uniform(-3, 3), "origin": "watch"})
        rhr.append({"t": base + 8 * 3600_000, "bpm": (70 if bad else 55) + rnd.uniform(-1, 1), "origin": "watch"})
        for t in range(base, base + DAY, 5 * MIN):
            hr.append({"t": t, "bpm": 60 + rnd.randint(0, 25), "origin": "watch"})
        for m in range(10 * 60, 11 * 60):  # an hour of walking
            steps.append({"start": base + m * MIN, "end": base + (m + 1) * MIN, "count": 100, "origin": "watch"})
        if i % 3 == 0:
            ex.append({"start": base + 17 * 3600_000, "end": base + 17 * 3600_000 + 30 * MIN,
                       "type": 56, "title": "Run", "origin": "watch"})
            hr += [{"t": base + 17 * 3600_000 + k * 10_000, "bpm": 150, "origin": "watch"} for k in range(180)]
    for typ, recs in (("heart_rate", hr), ("hrv", hrv), ("resting_hr", rhr), ("sleep", sleep),
                      ("steps", steps), ("exercise", ex)):
        api.post(typ, recs)

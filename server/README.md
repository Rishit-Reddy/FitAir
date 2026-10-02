# FitAir server

FastAPI + DuckDB receiver and coach API for the FitAir phone app. Contract: [API.md](API.md).
Store: `server/data/fitair.duckdb` (gitignored).

## Setup (Mac)

```bash
cd server
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
./run.sh                     # = python app.py serve, binds 0.0.0.0:8787
```

Env vars: `FITAIR_PORT` (8787), `FITAIR_HOST` (0.0.0.0), `FITAIR_MAXHR` (190, used for HR zones and load
intensity), `FITAIR_TZ` (Europe/Stockholm, default for `tz=`), `FITAIR_DB`, `FITAIR_API_KEY`.

## API key

Created on first run in `server/.apikey` (also printed once). Read it with `cat server/.apikey`; send it as
header `X-Api-Key`. Setting `FITAIR_API_KEY` overrides the file.

```bash
curl -H "X-Api-Key: $(cat .apikey)" http://localhost:8787/health
```

## Reaching it from the phone (Tailscale)

Install Tailscale on the Mac and phone, same tailnet. Use the Mac's `100.x.y.z` address (`tailscale ip -4`) or its
MagicDNS name (e.g. `my-mac.tailnet-name.ts.net`): `http://100.x.y.z:8787`. Plain HTTP is fine inside the tailnet
(traffic is WireGuard-encrypted); the phone app may need cleartext allowed for that host.

## Keep the Mac awake

```bash
caffeinate -s ./run.sh       # no system sleep while on power, for as long as the server runs
```

Optional launchd agent (`~/Library/LaunchAgents/dev.fitair.server.plist`, edit the path, then
`launchctl load ~/Library/LaunchAgents/dev.fitair.server.plist`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>dev.fitair.server</string>
  <key>ProgramArguments</key><array>
    <string>/usr/bin/caffeinate</string><string>-s</string>
    <string>/Users/YOU/FitAir/server/run.sh</string></array>
  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/fitair.log</string>
  <key>StandardErrorPath</key><string>/tmp/fitair.log</string>
</dict></plist>
```

## Export to Parquet

```bash
python app.py export --out data/parquet     # one .parquet per table
```
DuckDB allows one process on the file, so stop the server first (or copy `data/fitair.duckdb`).

## Tests

```bash
pytest tests          # synthetic data, temp DB
```

## Notes on the calculations

- Days are local (`tz`), DST-aware. Sleep counts toward the day it ends; overnight HRV = mean of HRV readings inside
  that day's sleep sessions. Steps, distance and calories take, per minute, the origin with the larger value.
- `active_minutes_est` = minutes with >= 60 steps.
- Readiness (0-100, our own): sleep 35% (duration vs 28-day mean), HRV 30% (z-score), resting HR 20% (inverted
  z-score), load 15% (last 7 days of minutes x HR/maxHR vs the 28-day weekly average). A typical day scores ~75 per
  component. Missing components are dropped and the weights renormalised; with < 7 baseline days or < 2 components
  `score` is `null` with an explanatory note. `notes` lists the biggest drivers.
- Workouts: `hr_recovery_1min` = HR at the end minus HR 45-75 s after; `drift_pct` = second-half vs first-half mean HR
  after a 10% warm-up (needs >= 10 min); zones are 50-60-70-80-90-100% of `FITAIR_MAXHR`.

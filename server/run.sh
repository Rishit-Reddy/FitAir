#!/usr/bin/env bash
# Start the FitAir server on 0.0.0.0:$FITAIR_PORT (default 8787).
cd "$(dirname "$0")"
[ -f .venv/bin/activate ] && . .venv/bin/activate
exec python app.py serve

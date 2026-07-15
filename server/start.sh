#!/bin/bash
# Start script for production
# Uses gunicorn with uvicorn workers for high concurrency

umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

# Default to 4 workers (adjust based on CPU cores, e.g., 2 x cores + 1)
# You can override this by setting the WORKERS environment variable
WORKERS=${WORKERS:-4}
PORT=${PORT:-16384}
HOST=${HOST:-0.0.0.0}
AUTH_DB_PATH=${AUTH_DB_PATH:-/tmp/bit-login/auth.db}
export AUTH_DB_PATH

# Add the project root to PYTHONPATH to ensure bit_login can be imported.
export PYTHONPATH="$PROJECT_ROOT${PYTHONPATH:+:$PYTHONPATH}"

echo "Starting BIT Login Server with $WORKERS workers on $HOST:$PORT..."

mkdir -p "$(dirname "$AUTH_DB_PATH")"

# Exec gunicorn to replace the shell process
exec gunicorn server:app \
    --workers $WORKERS \
    --worker-class uvicorn.workers.UvicornWorker \
    --bind $HOST:$PORT \
    --access-logfile - \
    --error-logfile - \
    --log-level info

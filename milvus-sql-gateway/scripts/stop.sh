#!/usr/bin/env bash
cd "$(dirname "$0")/.." || exit 1
DEPLOY_DIR=$(pwd)
PID_FILE="$DEPLOY_DIR/bin/milvus-sql-gateway.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "PID file not found. Server may not be running."
    exit 1
fi

PID=$(cat "$PID_FILE")

if ! kill -0 "$PID" 2>/dev/null; then
    echo "Server is not running (stale PID file removed)."
    rm -f "$PID_FILE"
    exit 0
fi

echo "Stopping server (PID: $PID)..."
kill "$PID"

WAITED=0
MAX_WAIT=30
while kill -0 "$PID" 2>/dev/null; do
    sleep 1
    WAITED=$((WAITED + 1))
    if [ "$WAITED" -ge "$MAX_WAIT" ]; then
        echo "Server did not stop gracefully within ${MAX_WAIT}s, forcing kill..."
        kill -9 "$PID" 2>/dev/null || true
        break
    fi
done

rm -f "$PID_FILE"
echo "Server stopped."

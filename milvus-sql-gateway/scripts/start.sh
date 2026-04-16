#!/usr/bin/env bash
cd "$(dirname "$0")/.." || exit 1
DEPLOY_DIR=$(pwd)
LIB_DIR="$DEPLOY_DIR/lib"
CONF_DIR="$DEPLOY_DIR/conf"
LOG_DIR="$DEPLOY_DIR/logs"
PID_FILE="$DEPLOY_DIR/bin/milvus-sql-gateway.pid"

mkdir -p "$LOG_DIR"

LIB_JARS=$(find "$LIB_DIR" -name "*.jar" | tr '\n' ':')

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Server is already running (PID: $(cat "$PID_FILE"))"
    exit 1
fi

nohup java -cp "$CONF_DIR:$LIB_JARS" org.apache.calcite.adapter.milvus.sql.client.MilvusMySQLServerBootstrap "$@" > "$LOG_DIR/milvus-sql-gateway.log" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"
echo "Server started in background (PID: $PID)"

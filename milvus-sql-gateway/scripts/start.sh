#!/usr/bin/env bash
cd "$(dirname "$0")/.." || exit 1
DEPLOY_DIR=$(pwd)
LIB_DIR="$DEPLOY_DIR/lib"
CONF_DIR="$DEPLOY_DIR/conf"

LIB_JARS=$(find "$LIB_DIR" -name "*.jar" | tr '\n' ':')

exec java -cp "$CONF_DIR:$LIB_JARS" org.apache.calcite.adapter.milvus.sql.client.MilvusMySQLServerBootstrap "$@"

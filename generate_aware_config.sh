#!/bin/sh

cat aware-config.json.template | \
  sed "s/%DB_ENGINE%/$DB_ENGINE/" | \
  sed "s/%DB_HOST%/$DB_HOST/" | \
  sed "s/%DB_NAME%/$DB_NAME/" | \
  sed "s/%DB_USER%/$DB_USER/" | \
  sed "s/%DB_PWD%/$DB_PWD/" | \
  sed "s/%DB_PORT%/$DB_PORT/" | \
  sed "s|%SERVER_HOST%|$SERVER_HOST|" | \
  sed "s/%SERVER_PORT%/$SERVER_PORT/" | \
  sed "s/%WEBSOCKET_PORT%/$WEBSOCKET_PORT/" | \
  sed "s/%STUDY_START%/$STUDY_START/" > aware-config.json

mkdir -p src/main/resources/cache

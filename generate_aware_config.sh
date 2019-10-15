#!/bin/sh

cat aware-config.json.template | \
  sed "s/%AWARE_MICRO_DB_ENGINE%/$DB_ENGINE/" | \
  sed "s/%AWARE_MICRO_DB_NAME%/$DB_NAME/" | \
  sed "s/%AWARE_MICRO_DB_USER%/$DB_USER/" | \
  sed "s/%AWARE_MICRO_DB_PWD%/$DB_PWD/" | \
  sed "s/%AWARE_MICRO_DB_PORT%/$DB_PORT/" | \
  sed "s/%AWARE_MICRO_SERVER_DOMAIN%/$SERVER_DOMAIN/" | \
  sed "s/%AWARE_MICRO_API_PORT%/$API_PORT/" > aware-config.json

cat aware-config.json

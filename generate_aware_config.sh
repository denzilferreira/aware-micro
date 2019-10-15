#!/bin/sh

cat aware-config.json.template | \
  sed "s/%DB_ENGINE%/$DB_ENGINE/" | \
  sed "s/%DB_NAME%/$DB_NAME/" | \
  sed "s/%DB_USER%/$DB_USER/" | \
  sed "s/%DB_PWD%/$DB_PWD/" | \
  sed "s/%DB_PORT%/$DB_PORT/" | \
  sed "s/%SERVER_DOMAIN%/$SERVER_DOMAIN/" | \
  sed "s/%API_PORT%/$API_PORT/" > aware-config.json

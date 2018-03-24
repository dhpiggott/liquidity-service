#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 host username password from"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker run \
  --rm \
  --volume $DIR/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
  mysql:5 \
  mysql \
    --ssl-ca=/rds-combined-ca-bundle.pem \
    --ssl-mode=VERIFY_IDENTITY \
    --host=$1 \
    --user=$2 \
    --password=$3 \
    liquidity_analytics -e " \
      SELECT zones.zone_id, zones.created, zones.modified, zones.expires, \
      zones.metadata->'$.currency' AS currency, \
      LEFT(zone_name_changes.name, 20) \
      FROM zones \
      JOIN zone_name_changes \
      ON zone_name_changes.change_id = ( \
      SELECT zone_name_changes.change_id \
        FROM zone_name_changes \
        WHERE zone_name_changes.zone_id = zones.zone_id \
        AND zone_name_changes.name != 'Test' \
        ORDER BY change_id DESC LIMIT 1 \
      ) \
      WHERE zones.created >= '$4' \
      AND NOT zones.metadata->'$.isTest' IS true \
      ORDER BY created; \
    "

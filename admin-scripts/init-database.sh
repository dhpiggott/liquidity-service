#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 host username password schema"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker run \
  --rm \
  --volume $DIR/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
  --volume $DIR/../schemas/$4.sql:/schema.sql \
  mysql:5 \
  sh -c " \
    mysql \
    --ssl-ca=/rds-combined-ca-bundle.pem \
    --ssl-mode=VERIFY_IDENTITY \
    --host=$1 \
    --user=$2 \
    --password=$3 \
    < /schema.sql \
  "

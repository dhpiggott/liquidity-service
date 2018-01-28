#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 input-directory host username password"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker run \
    --rm \
    --volume $DIR/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
    --volume $1/journal_dump.sql:/dump.sql \
    mysql:5 \
    sh -c " \
      mysql \
        --ssl-ca=/rds-combined-ca-bundle.pem \
        --ssl-mode=VERIFY_IDENTITY \
        --host=$2 \
        --user=$3 \
        --password=$4 \
        liquidity_journal < /dump.sql \
    "

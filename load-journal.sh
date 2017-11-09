#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 input-directory host username password"
    exit 1
fi

docker run --rm \
    --volume $1/journal_dump.sql:/journal_dump.sql \
    mysql:5 sh -c 'mysql --host='$2' --user='$3' --password='$4' liquidity_journal < /journal_dump.sql'

#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 output-directory host username password"
    exit 1
fi

mkdir --parents $1
touch $1/journal_dump.sql

docker run --rm \
    --volume $1/journal_dump.sql:/journal_dump.sql \
    mysql:5 sh -c 'mysqldump --host='$2' --user='$3' --password='$4' --skip-opt --no-create-info --add-locks --disable-keys --extended-insert --quick liquidity_journal > /journal_dump.sql'

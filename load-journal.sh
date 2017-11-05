#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 input-directory"
    exit 1
fi

docker-compose run -T --rm \
    --volume $1/journal_dump.sql:/root/journal_dump.sql \
    mysql sh -c 'mysql --host=mysql liquidity_journal < /root/journal_dump.sql'

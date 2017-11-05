#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 input-directory"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker-compose --file $DIR/docker-compose.yml --project-name liquidity run -T --rm \
    --volume $1/journal_dump.sql:/root/journal_dump.sql \
    mysql sh -c 'mysql --host=mysql liquidity_journal < /root/journal_dump.sql'

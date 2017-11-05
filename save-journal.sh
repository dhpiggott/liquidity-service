#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 output-directory"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

mkdir --parents $1
touch $1/journal_dump.sql

docker-compose --file $DIR/docker-compose.yml --project-name liquidity run -T --rm \
    --volume $1/journal_dump.sql:/root/journal_dump.sql \
    mysql sh -c 'mysqldump --host=mysql --skip-opt --no-create-info --add-locks --disable-keys --extended-insert --quick liquidity_journal > /root/journal_dump.sql'

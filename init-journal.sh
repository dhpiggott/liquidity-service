#!/bin/bash

set -euo pipefail

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker-compose --file $DIR/docker-compose.yml --project-name liquidity run -T --rm \
    --volume $DIR/journal.sql:/root/journal.sql \
    mysql sh -c 'mysql --host=mysql < /root/journal.sql'

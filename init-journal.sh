#!/bin/bash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker-compose run -T --rm \
    --volume $DIR/journal.sql:/root/journal.sql \
    mysql sh -c 'mysql --host=mysql < /root/journal.sql'

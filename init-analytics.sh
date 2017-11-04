#!/bin/bash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker-compose run -T --rm \
    --volume $DIR/analytics.sql:/root/analytics.sql \
    mysql sh -c 'mysql --host=mysql < /root/analytics.sql'

#!/bin/bash

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker-compose run -T --rm \
    --volume $DIR/analytics.sql:/analytics.sql \
    mysql sh -c 'mysql -h mysql < /analytics.sql'

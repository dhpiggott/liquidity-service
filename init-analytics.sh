#!/bin/bash

set -euo pipefail

if [ $# -ne 1 ]
  then
    echo "Usage: $0 mysql-host"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

read -p "Username: " USERNAME
read -p "Password: " -s PASSWORD

docker run --interactive --tty --rm \
    --volume $DIR/analytics.sql:/root/analytics.sql \
    mysql sh -c 'mysql --host='$1' --user='$USERNAME' --password='$PASSWORD' < /root/analytics.sql'

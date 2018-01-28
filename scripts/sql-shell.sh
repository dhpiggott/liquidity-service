#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 host username password"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker run \
    --interactive \
    --tty \
    --rm \
    --volume $DIR/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
    mysql:5 \
    mysql \
        --ssl-ca=/rds-combined-ca-bundle.pem \
        --ssl-mode=VERIFY_IDENTITY \
        --host=$1 \
        --user=$2 \
        --password=$3

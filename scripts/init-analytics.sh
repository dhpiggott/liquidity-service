#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 host username password"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker run --rm \
    --volume $DIR/../schemas/analytics.sql:/analytics.sql \
    mysql:5 sh -c 'mysql --host='$1' --user='$2' --password='$3' < /analytics.sql'

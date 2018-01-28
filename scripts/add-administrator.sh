#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 host username password private-path"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

PUBLIC_KEY=$(
    openssl rsa \
    -in $4 \
    -pubout \
    -outform DER 2> /dev/null |
    xxd -plain |
    tr -d '[:space:]'
)

docker run \
    --rm \
    --volume $DIR/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
    mysql:5 \
    mysql \
        --ssl-ca=/rds-combined-ca-bundle.pem \
        --ssl-mode=VERIFY_IDENTITY \
        --host=$1 \
        --user=$2 \
        --password=$3 \
        liquidity_administrators -e " \
          INSERT INTO administrators (public_key) \
            VALUES (x'$PUBLIC_KEY') \
        "

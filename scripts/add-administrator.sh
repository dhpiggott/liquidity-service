#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 host username password private-path"
    exit 1
fi

PUBLIC_KEY=$(
    openssl rsa \
    -in $4 \
    -pubout \
    -outform DER 2> /dev/null |
    xxd -plain |
    tr -d '[:space:]'
)

docker run --rm \
    mysql:5 mysql --host=$1 --user=$2 --password=$3 \
        -e "INSERT INTO liquidity_administrators.administrators (public_key) \
              VALUES (x'$PUBLIC_KEY')"

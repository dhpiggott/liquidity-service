#!/bin/bash

set -euo pipefail

if [[ $# -ne 1 ]]
  then
    echo "Usage: $0 private-key-path"
    exit 1
fi

PRIVATE_KEY_PATH=$1

openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:2048 \
  -out "$PRIVATE_KEY_PATH" 2> /dev/null

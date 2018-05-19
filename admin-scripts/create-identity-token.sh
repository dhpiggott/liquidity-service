#!/bin/bash

set -euo pipefail

if [ $# -ne 1 ]
  then
    echo "Usage: $0 private-key-path"
    exit 1
fi

PRIVATE_KEY_PATH=$1

SUB=$(
  openssl rsa \
  -in "$PRIVATE_KEY_PATH" \
  -pubout \
  -outform DER 2> /dev/null |
  base64 --wrap=0
)

IAT=$(date +%s)

EXP=$(("$IAT" + 3600))

jwt \
  --encode \
  --algorithm RS256 \
  --private-key-file "$PRIVATE_KEY_PATH" \
  --timestamp \
  "{\"sub\":\"$SUB\",\"exp\":$EXP}"

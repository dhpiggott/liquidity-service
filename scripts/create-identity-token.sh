#!/bin/bash

set -euo pipefail

if [ $# -ne 1 ]
  then
    echo "Usage: $0 private-key-path"
    exit 1
fi

SUB=$(
    openssl rsa \
    -in $1 \
    -pubout \
    -outform DER 2> /dev/null |
    base64 --wrap=0
)

IAT=$(date +%s)

EXP=$(expr $IAT + 3600)

jwt \
    --encode \
    --algorithm RS256 \
    --private-key-file $1 \
    --timestamp \
    "{\"sub\":\"$SUB\",\"exp\":$EXP}"

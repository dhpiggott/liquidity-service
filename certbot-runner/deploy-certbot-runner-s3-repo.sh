#!/bin/bash

set -euo pipefail

if [ $# -ne 1 ]
  then
    echo "Usage: $0 region"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-certbot-runner-s3-repo \
  --template-file "$DIR"/liquidity-certbot-runner-s3-repo.yaml \
  --no-fail-on-empty-changeset

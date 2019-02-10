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
  --stack-name liquidity-grafana-iam-user \
  --template-file "$DIR"/../cfn-templates/liquidity-grafana-iam-user.yaml \
  --no-fail-on-empty-changeset \
  --capabilities CAPABILITY_NAMED_IAM

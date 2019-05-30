#!/bin/bash

set -euo pipefail

if [[ $# -ne 2 ]]
  then
    echo "Usage: $0 region subdomain"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
SUBDOMAIN=$2

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-certbot-runner-infrastructure-"$SUBDOMAIN" \
  --template-file "$DIR"/../cfn-templates/liquidity-certbot-runner-infrastructure.yaml \
  --no-fail-on-empty-changeset

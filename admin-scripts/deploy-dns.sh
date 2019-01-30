#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 region environment subdomain"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
ENVIRONMENT=$2
SUBDOMAIN=$3

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-dns-"$SUBDOMAIN" \
  --template-file "$DIR"/../cfn-templates/liquidity-dns.yaml \
  --no-fail-on-empty-changeset \
  --parameter-overrides \
      InfrastructureStack=liquidity-infrastructure-"$ENVIRONMENT" \
      Subdomain="$SUBDOMAIN"

#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 region subdomain network-environment"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
SUBDOMAIN=$2
NETWORK_ENVIRONMENT=$3

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-dns-"$SUBDOMAIN" \
  --template-file "$DIR"/../cfn-templates/liquidity-dns.yaml \
  --no-fail-on-empty-changeset \
  --parameter-overrides \
      Subdomain="$SUBDOMAIN" \
      NetworkStack=liquidity-network-"$NETWORK_ENVIRONMENT"

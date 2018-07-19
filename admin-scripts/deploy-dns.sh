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

if ! aws cloudformation describe-stacks \
       --region "$REGION" \
       --stack-name liquidity-dns-"$SUBDOMAIN"
then
  ACTION="create"
else
  ACTION="update"
fi

aws cloudformation "$ACTION"-stack \
  --region "$REGION" \
  --stack-name liquidity-dns-"$SUBDOMAIN" \
  --template-body file://"$DIR"/../cfn-templates/liquidity-dns.yaml \
  --parameters \
    ParameterKey=InfrastructureStack,ParameterValue=liquidity-infrastructure-"$ENVIRONMENT" \
    ParameterKey=Subdomain,ParameterValue="$SUBDOMAIN"

aws cloudformation wait stack-"$ACTION"-complete \
  --region "$REGION" \
  --stack-name liquidity-dns-"$SUBDOMAIN"

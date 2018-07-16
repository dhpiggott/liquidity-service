#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 region environment subdomain"
    exit 1
fi

REGION=$1
ENVIRONMENT=$2
SUBDOMAIN=$3

MYSQL_HOSTNAME=$(
  aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name "liquidity-infrastructure-$ENVIRONMENT" \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSHostname'].OutputValue"
)
MYSQL_USERNAME=$(
  aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name "liquidity-infrastructure-$ENVIRONMENT" \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSUsername'].OutputValue"
)
MYSQL_PASSWORD=$(
  aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name "liquidity-infrastructure-$ENVIRONMENT" \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSPassword'].OutputValue"
)

(export SUBDOMAIN MYSQL_HOSTNAME MYSQL_USERNAME MYSQL_PASSWORD && \
  sbt ";server/it:testOnly *LiquidityServerIntegrationSpec")

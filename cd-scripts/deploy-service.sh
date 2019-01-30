#!/bin/bash

set -euo pipefail

if [ $# -ne 2 ]
  then
    echo "Usage: $0 region environment"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
ENVIRONMENT=$2

VPC_ID=$(
  aws ec2 describe-vpcs \
    --region "$REGION" \
    --filters \
      Name=isDefault,Values=true \
    --output text \
    --query \
      "Vpcs[0].VpcId"
)
SUBNETS=$(
  aws ec2 describe-subnets \
    --region "$REGION" \
    --filter \
      Name=vpcId,Values="$VPC_ID" \
      Name=defaultForAz,Values=true \
    --output text \
    --query \
      "Subnets[].SubnetId | join(',', @)"
)
TAG=$(
  git describe \
    --always \
    --dirty
)

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-service-"$ENVIRONMENT" \
  --template-file "$DIR"/../cfn-templates/liquidity-service.yaml \
  --no-fail-on-empty-changeset \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
      InfrastructureStack=liquidity-infrastructure-"$ENVIRONMENT" \
      Subnets="$SUBNETS" \
      Tag="$TAG"

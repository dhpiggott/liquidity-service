#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 <create|update> region environment"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

case $1 in
  create | update)
    ACTION=$1
    ;;
  *)
    echo "Usage: $0 <create|update> region environment"
    exit 1
    ;;
esac

REGION=$2
ENVIRONMENT=$3

case $ENVIRONMENT in
  prod)
    DOMAIN_PREFIX=
    ;;
  *)
    DOMAIN_PREFIX=$ENVIRONMENT-
    ;;
esac

VPC_ID=$(
  aws ec2 describe-vpcs \
    --region $REGION \
    --filters \
      Name=isDefault,Values=true \
    --output text \
    --query \
      "Vpcs[0].VpcId"
)
SUBNETS=$(
  aws ec2 describe-subnets \
    --region $REGION \
    --filter \
      Name=vpcId,Values=$VPC_ID \
      Name=defaultForAz,Values=true \
    --output text \
    --query \
      "Subnets[].SubnetId | join(',', @)"
)
TAG=evergreen-$(
  git describe \
    --always \
    --dirty
)

INFRASTRUCTURE_STACK=liquidity-infrastructure-$ENVIRONMENT

aws cloudformation $ACTION-stack \
  --region $REGION \
  --stack-name liquidity-service-$ENVIRONMENT \
  --template-body file://$DIR/../cfn-templates/liquidity-service.yaml \
  --capabilities CAPABILITY_IAM \
  --parameters \
    ParameterKey=InfrastructureStack,ParameterValue=$INFRASTRUCTURE_STACK \
    ParameterKey=Subnets,ParameterValue=\"$SUBNETS\" \
    ParameterKey=Tag,ParameterValue=$TAG

aws cloudformation wait stack-$ACTION-complete \
  --region $REGION \
  --stack-name liquidity-service-$ENVIRONMENT

(export DOMAIN_PREFIX && sbt ";server/it:testOnly *LiquidityServerIntegrationSpec")

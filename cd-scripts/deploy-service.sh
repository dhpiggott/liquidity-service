#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 <create|update> region environment subdomain"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

case $1 in
  create | update)
    ACTION=$1
    ;;
  *)
    echo "Usage: $0 <create|update> region environment subdomain"
    exit 1
    ;;
esac

REGION=$2
ENVIRONMENT=$3
SUBDOMAIN=$4

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

INFRASTRUCTURE_STACK=liquidity-infrastructure-$ENVIRONMENT

aws cloudformation "$ACTION"-stack \
  --region "$REGION" \
  --stack-name liquidity-service-"$ENVIRONMENT" \
  --template-body file://"$DIR"/../cfn-templates/liquidity-service.yaml \
  --capabilities CAPABILITY_IAM \
  --parameters \
    ParameterKey=InfrastructureStack,ParameterValue="$INFRASTRUCTURE_STACK" \
    ParameterKey=Subnets,ParameterValue=\""$SUBNETS"\" \
    ParameterKey=Tag,ParameterValue="$TAG"

aws cloudformation wait stack-"$ACTION"-complete \
  --region "$REGION" \
  --stack-name liquidity-service-"$ENVIRONMENT"

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

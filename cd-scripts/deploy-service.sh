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

if ! aws cloudformation describe-stacks \
       --region "$REGION" \
       --stack-name liquidity-service-"$ENVIRONMENT"
then
  ACTION="create"
else
  ACTION="update"
fi

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

aws cloudformation "$ACTION"-stack \
  --region "$REGION" \
  --stack-name liquidity-service-"$ENVIRONMENT" \
  --template-body file://"$DIR"/../cfn-templates/liquidity-service.yaml \
  --capabilities CAPABILITY_IAM \
  --parameters \
    ParameterKey=InfrastructureStack,ParameterValue=liquidity-infrastructure-"$ENVIRONMENT" \
    ParameterKey=Subnets,ParameterValue=\""$SUBNETS"\" \
    ParameterKey=Tag,ParameterValue="$TAG"

aws cloudformation wait stack-"$ACTION"-complete \
  --region "$REGION" \
  --stack-name liquidity-service-"$ENVIRONMENT"

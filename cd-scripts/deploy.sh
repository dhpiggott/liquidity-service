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
    STACK_SUFFIX=
    ;;
  *)
    DOMAIN_PREFIX=$ENVIRONMENT-
    STACK_SUFFIX=-$ENVIRONMENT
    ;;
esac

AWS_ACCOUNT_ID=$(
  aws sts get-caller-identity \
    --output text \
    --query \
      "Account"
)
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

eval $(
  aws ecr get-login \
    --region eu-west-1 \
    --no-include-email
)
eval $(
  aws ecr get-login \
    --region $REGION \
    --no-include-email
)

INFRASTRUCTURE_STACK=liquidity-infrastructure$STACK_SUFFIX

docker pull \
  $AWS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com/liquidity-ci:$TAG

docker tag \
  $AWS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com/liquidity-ci:$TAG \
  $AWS_ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/$INFRASTRUCTURE_STACK:$TAG

docker push \
  $AWS_ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/$INFRASTRUCTURE_STACK:$TAG

docker rmi \
  $AWS_ACCOUNT_ID.dkr.ecr.eu-west-1.amazonaws.com/liquidity-ci:$TAG

docker rmi \
  $AWS_ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/$INFRASTRUCTURE_STACK:$TAG

aws cloudformation $ACTION-stack \
  --region $REGION \
  --stack-name liquidity$STACK_SUFFIX \
  --template-body file://$DIR/../cfn-templates/liquidity.yaml \
  --capabilities CAPABILITY_IAM \
  --parameters \
    ParameterKey=InfrastructureStack,ParameterValue=$INFRASTRUCTURE_STACK \
    ParameterKey=Subnets,ParameterValue=\"$SUBNETS\" \
    ParameterKey=Tag,ParameterValue=$TAG

aws cloudformation wait stack-$ACTION-complete \
  --region $REGION \
  --stack-name liquidity$STACK_SUFFIX

(export DOMAIN_PREFIX && sbt ";client-simulation/gatling:test")

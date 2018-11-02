#!/bin/bash

set -euo pipefail

if [ $# -ne 2 ]
  then
    echo "Usage: region environment"
    exit 1
fi

REGION=$1
ENVIRONMENT=$2

AWS_ACCOUNT_ID=$(
  aws sts get-caller-identity \
    --output text \
    --query 'Account'
)
TAG=$(
  git describe \
    --always \
    --dirty
)

ECR_LOGIN_COMMAND=$(
  aws ecr get-login \
    --region "$REGION" \
    --no-include-email
)
$ECR_LOGIN_COMMAND

INFRASTRUCTURE_STACK=liquidity-infrastructure-$ENVIRONMENT

sbt ";server/docker:publishLocal"

docker tag \
  liquidity:"$TAG" \
  "$AWS_ACCOUNT_ID".dkr.ecr."$REGION".amazonaws.com/"$INFRASTRUCTURE_STACK":"$TAG"

docker push \
  "$AWS_ACCOUNT_ID".dkr.ecr."$REGION".amazonaws.com/"$INFRASTRUCTURE_STACK":"$TAG"

docker rmi \
  liquidity:"$TAG"

docker rmi \
  "$AWS_ACCOUNT_ID".dkr.ecr."$REGION".amazonaws.com/"$INFRASTRUCTURE_STACK":"$TAG"

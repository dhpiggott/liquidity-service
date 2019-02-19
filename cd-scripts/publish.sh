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
  sbt -Dsbt.log.noformat=true version \
    | tail -n 1 \
    | cut -d " " -f 2 \
    | tr -d "[:blank:]"
)

ECR_LOGIN_COMMAND=$(
  aws ecr get-login \
    --region "$REGION" \
    --no-include-email
)
$ECR_LOGIN_COMMAND

sbt service/docker:publishLocal

docker tag \
  liquidity:"$TAG" \
  "$AWS_ACCOUNT_ID".dkr.ecr."$REGION".amazonaws.com/liquidity-infrastructure-"$ENVIRONMENT":"$TAG"

docker push \
  "$AWS_ACCOUNT_ID".dkr.ecr."$REGION".amazonaws.com/liquidity-infrastructure-"$ENVIRONMENT":"$TAG"

docker rmi \
  liquidity:"$TAG"

docker rmi \
  "$AWS_ACCOUNT_ID".dkr.ecr."$REGION".amazonaws.com/liquidity-infrastructure-"$ENVIRONMENT":"$TAG"

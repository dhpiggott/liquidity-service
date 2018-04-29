#!/bin/bash

set -euo pipefail

if [ $# -ne 2 ]
  then
    echo "Usage: region environment"
    exit 1
fi

REGION=$1
ENVIRONMENT=$2

case $ENVIRONMENT in
  prod)
    STACK_SUFFIX=
    ;;
  *)
    STACK_SUFFIX=-$ENVIRONMENT
    ;;
esac

AWS_ACCOUNT_ID=$(
  aws sts get-caller-identity \
    --output text \
    --query 'Account'
)
TAG=evergreen-$(
  git describe \
    --always \
    --dirty
)

eval $(
  aws ecr get-login \
    --region $REGION \
    --no-include-email
)

INFRASTRUCTURE_STACK=liquidity-infrastructure$STACK_SUFFIX

sbt ";server/it:scalafmt::test ;server/docker:publishLocal ;server/it:test"

docker tag \
  liquidity:$TAG \
  $AWS_ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/$INFRASTRUCTURE_STACK:$TAG

docker push \
  $AWS_ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/$INFRASTRUCTURE_STACK:$TAG

docker rmi \
  liquidity:$TAG

docker rmi \
  $AWS_ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/$INFRASTRUCTURE_STACK:$TAG

#!/bin/bash

set -euo pipefail

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
    --region us-east-1 \
    --no-include-email
)

sbt ";server/it:scalafmt::test ;server/docker:publishLocal ;server/it:test"

docker tag \
  liquidity:$TAG \
  $AWS_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/liquidity-ci:$TAG

docker push \
  $AWS_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/liquidity-ci:$TAG

docker rmi \
  liquidity:$TAG

docker rmi \
  $AWS_ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/liquidity-ci:$TAG

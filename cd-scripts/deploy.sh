#!/bin/bash

set -euo pipefail

TAG=evergreen-$(git describe --always --dirty)

eval $(aws ecr get-login --region us-east-1 --no-include-email)

sbt ";server/docker:publishLocal"

docker tag \
  liquidity:$TAG \
  837036139524.dkr.ecr.us-east-1.amazonaws.com/liquidity:$TAG

docker push \
  837036139524.dkr.ecr.us-east-1.amazonaws.com/liquidity:$TAG

sbt ";server/docker:clean"

docker rmi \
  837036139524.dkr.ecr.us-east-1.amazonaws.com/liquidity:$TAG

aws cloudformation update-stack \
  --region us-east-1 \
  --stack-name liquidity-application \
  --use-previous-template \
  --capabilities CAPABILITY_IAM \
  --parameters \
    ParameterKey=Stack,ParameterValue=liquidity-infrastructure \
    ParameterKey=Subnets,ParameterValue=\"subnet-fd6f218a,subnet-2d73e606,subnet-9f2644c6,subnet-2c32b849,subnet-1eaa1512,subnet-a9e5dd93\" \
    ParameterKey=Tag,ParameterValue=$TAG

aws cloudformation wait stack-update-complete \
  --region us-east-1 \
  --stack-name liquidity-application

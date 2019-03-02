#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 region environment infrastructure-stack-environment"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
ENVIRONMENT=$2
INFRASTRUCTURE_STACK_ENVIRONMENT=$3

TAG=$(
  sbt -Dsbt.log.noformat=true version \
    | tail -n 1 \
    | cut -d " " -f 2 \
    | tr -d "[:blank:]"
)
IMAGE_ID=$(
  aws ecr describe-images \
    --region "$REGION" \
    --repository liquidity-infrastructure-"$INFRASTRUCTURE_STACK_ENVIRONMENT" \
    --image-ids imageTag="$TAG" \
    --output text \
    --query \
      "imageDetails[0].imageDigest"
)

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-service-"$ENVIRONMENT" \
  --template-file "$DIR"/../cfn-templates/liquidity-service.yaml \
  --no-fail-on-empty-changeset \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
      InfrastructureStack=liquidity-infrastructure-"$INFRASTRUCTURE_STACK_ENVIRONMENT" \
      ImageId="$IMAGE_ID"

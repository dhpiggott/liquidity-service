#!/bin/bash

set -euo pipefail

if [ $# -ne 2 ]
  then
    echo "Usage: $0 region subdomain"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
SUBDOMAIN=$2

HOSTED_ZONE_ID=$(
  aws route53 list-hosted-zones \
    --region "$REGION" \
    --output text \
    --query \
      "HostedZones[?Name=='liquidityapp.com.'] \
      | [0].Id"
)

sam build \
  --template "$DIR"/liquidity-certbot-runner.yaml \
  --use-container

sam package \
  --region "$REGION" \
  --s3-bucket "$REGION".liquidity-certbot-runner \
  --output-template-file "$DIR"/../.aws-sam/certbot-runner-"$SUBDOMAIN".yaml

sam deploy \
  --region "$REGION" \
  --stack-name liquidity-certbot-runner-"$SUBDOMAIN" \
  --template-file "$DIR"/../.aws-sam/certbot-runner-"$SUBDOMAIN".yaml \
  --no-fail-on-empty-changeset \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
      HostedZoneId=${HOSTED_ZONE_ID#"/hostedzone/"} \
      Domains="$SUBDOMAIN".liquidityapp.com

LAMBDA_ID=$(
  aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name liquidity-certbot-runner-"$SUBDOMAIN" \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-certbot-runner-$SUBDOMAIN'] \
      | [0].Outputs[?OutputKey=='LambdaId'].OutputValue"
)

aws lambda invoke \
  --region "$REGION" \
  --function-name "$LAMBDA_ID" \
  --log-type Tail \
  /dev/stdout

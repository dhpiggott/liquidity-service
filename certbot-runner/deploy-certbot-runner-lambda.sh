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

if ! aws cloudformation describe-stacks \
       --region "$REGION" \
       --stack-name liquidity-certbot-runner-lambda-"$SUBDOMAIN"
then
  ACTION="create"
else
  ACTION="update"
fi

sam build \
  --template "$DIR"/liquidity-certbot-runner-lambda.yaml \
  --use-container

sam package \
  --region "$REGION" \
  --s3-bucket "$REGION".liquidity-certbot-runner-infrastructure-"$SUBDOMAIN" \
  --output-template-file "$DIR"/../.aws-sam/certbot-runner-lambda-"$SUBDOMAIN".yaml

if [ "$ACTION" = "create" ]
  then
    aws s3 cp \
      --region "$REGION" \
      "$DIR/empty.tar" \
      "s3://$REGION.liquidity-certbot-runner-infrastructure-$SUBDOMAIN/state.tar"
fi

sam deploy \
  --region "$REGION" \
  --stack-name liquidity-certbot-runner-lambda-"$SUBDOMAIN" \
  --template-file "$DIR"/../.aws-sam/certbot-runner-lambda-"$SUBDOMAIN".yaml \
  --no-fail-on-empty-changeset \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
      Subdomain="$SUBDOMAIN" \
      HostedZoneId="${HOSTED_ZONE_ID#"/hostedzone/"}"

LAMBDA_ID=$(
  aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name liquidity-certbot-runner-lambda-"$SUBDOMAIN" \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-certbot-runner-lambda-$SUBDOMAIN'] \
      | [0].Outputs[?OutputKey=='LambdaId'].OutputValue"
)

aws lambda invoke \
  --region "$REGION" \
  --function-name "$LAMBDA_ID" \
  --log-type Tail \
  /dev/stdout

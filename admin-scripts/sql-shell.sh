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

MYSQL_HOSTNAME=$(
  aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name liquidity-infrastructure-"$ENVIRONMENT" \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSHostname'].OutputValue"
)
MYSQL_USERNAME=$(
  aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name liquidity-infrastructure-"$ENVIRONMENT" \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSUsername'].OutputValue"
)
MYSQL_PASSWORD=$(
  aws cloudformation describe-stacks \
    --region "$REGION" \
    --stack-name liquidity-infrastructure-"$ENVIRONMENT" \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSPassword'].OutputValue"
)

docker run \
  --interactive \
  --tty \
  --rm \
  --volume "$DIR"/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
  mysql:8.0 \
  mysql \
    --ssl-ca=/rds-combined-ca-bundle.pem \
    --ssl-mode=VERIFY_IDENTITY \
    --host="$MYSQL_HOSTNAME" \
    --user="$MYSQL_USERNAME" \
    --password="$MYSQL_PASSWORD"

#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 region environment schema"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
ENVIRONMENT=$2
SCHEMA=$3

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
  --rm \
  --volume "$DIR"/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
  --volume "$DIR"/../schemas/"$SCHEMA".sql:/schema.sql \
  mysql:8.0 \
  sh -c " \
    mysql \
    --ssl-ca=/rds-combined-ca-bundle.pem \
    --ssl-mode=VERIFY_IDENTITY \
    --host=$MYSQL_HOSTNAME \
    --user=$MYSQL_USERNAME \
    --password=$MYSQL_PASSWORD \
    < /schema.sql \
  "

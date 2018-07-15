#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 region environment output-directory"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
ENVIRONMENT=$2
OUTPUT_DIRECTORY=$3

mkdir --parents "$OUTPUT_DIRECTORY"
touch "$OUTPUT_DIRECTORY"/journal_dump.sql

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
  --volume "$OUTPUT_DIRECTORY"/journal_dump.sql:/dump.sql \
  mysql:5 \
  sh -c " \
    mysqldump \
    --ssl-ca=/rds-combined-ca-bundle.pem \
    --ssl-mode=VERIFY_IDENTITY \
    --host=$MYSQL_HOSTNAME \
    --user=$MYSQL_USERNAME \
    --password=$MYSQL_PASSWORD \
    --skip-opt \
    --no-create-info \
    --add-locks \
    --disable-keys \
    --extended-insert \
    --quick liquidity_journal > /dump.sql \
    "

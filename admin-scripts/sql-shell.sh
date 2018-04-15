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

case $ENVIRONMENT in
  prod)
    STACK_SUFFIX=
    ;;
  *)
    STACK_SUFFIX=-$ENVIRONMENT
    ;;
esac

RDS_HOSTNAME=$(
  aws cloudformation describe-stacks \
    --region $REGION \
    --stack-name liquidity-infrastructure$STACK_SUFFIX \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure$STACK_SUFFIX'] \
      | [0].Outputs[?OutputKey=='RDSHostname'].OutputValue"
)
RDS_USERNAME=$(
  aws cloudformation describe-stacks \
    --region $REGION \
    --stack-name liquidity-infrastructure$STACK_SUFFIX \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure$STACK_SUFFIX'] \
      | [0].Outputs[?OutputKey=='RDSUsername'].OutputValue"
)
RDS_PASSWORD=$(
  aws cloudformation describe-stacks \
    --region $REGION \
    --stack-name liquidity-infrastructure$STACK_SUFFIX \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure$STACK_SUFFIX'] \
      | [0].Outputs[?OutputKey=='RDSPassword'].OutputValue"
)

docker run \
  --interactive \
  --tty \
  --rm \
  --volume $DIR/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
  mysql:5 \
  mysql \
    --ssl-ca=/rds-combined-ca-bundle.pem \
    --ssl-mode=VERIFY_IDENTITY \
    --host=$RDS_HOSTNAME \
    --user=$RDS_USERNAME \
    --password=$RDS_PASSWORD

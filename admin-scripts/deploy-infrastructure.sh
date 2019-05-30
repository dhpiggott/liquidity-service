#!/bin/bash

set -euo pipefail

if [[ $# -ne 2 ]]
  then
    echo "Usage: $0 region environment"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
ENVIRONMENT=$2

if ! aws cloudformation describe-stacks \
       --region "$REGION" \
       --stack-name liquidity-infrastructure-"$ENVIRONMENT"
then
  ACTION="create"
else
  ACTION="update"
fi

case ${ACTION} in
  create)
    MYSQL_USERNAME=liquidity
    MYSQL_PASSWORD=$(uuidgen)
    ;;
  update)
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
    ;;
esac

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-infrastructure-"$ENVIRONMENT" \
  --template-file "$DIR"/../cfn-templates/liquidity-infrastructure.yaml \
  --no-fail-on-empty-changeset \
  --parameter-overrides \
      RDSUsername="$MYSQL_USERNAME" \
      RDSPassword="$MYSQL_PASSWORD"

if [[ "$ACTION" = "create" ]]
  then
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" administrators
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" journal
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" analytics
fi

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

if ! aws cloudformation describe-stacks \
       --region "$REGION" \
       --stack-name liquidity-state-"$ENVIRONMENT"
then
  ACTION="create"
else
  ACTION="update"
fi

case $ACTION in
  create)
    MYSQL_USERNAME=liquidity
    MYSQL_PASSWORD=$(uuidgen)
    ;;
  update)
    MYSQL_USERNAME=$(
      aws cloudformation describe-stacks \
        --region "$REGION" \
        --stack-name liquidity-state-"$ENVIRONMENT" \
        --output text \
        --query \
          "Stacks[?StackName=='liquidity-state-$ENVIRONMENT'] \
          | [0].Outputs[?OutputKey=='RDSUsername'].OutputValue"
    )
    MYSQL_PASSWORD=$(
      aws cloudformation describe-stacks \
        --region "$REGION" \
        --stack-name liquidity-state-"$ENVIRONMENT" \
        --output text \
        --query \
          "Stacks[?StackName=='liquidity-state-$ENVIRONMENT'] \
          | [0].Outputs[?OutputKey=='RDSPassword'].OutputValue"
    )
    ;;
esac

VPC_ID=$(
  aws ec2 describe-vpcs \
    --region "$REGION" \
    --filters \
      Name=isDefault,Values=true \
    --output text \
    --query \
      "Vpcs[0].VpcId"
)
SUBNETS=$(
  aws ec2 describe-subnets \
    --region "$REGION" \
    --filter \
      Name=vpcId,Values="$VPC_ID" \
      Name=defaultForAz,Values=true \
    --output text \
    --query \
      "Subnets[].SubnetId | join(',', @)"
)

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-state-"$ENVIRONMENT" \
  --template-file "$DIR"/../cfn-templates/liquidity-state.yaml \
  --no-fail-on-empty-changeset \
  --parameter-overrides \
      Subnets="$SUBNETS" \
      RDSUsername="$MYSQL_USERNAME" \
      RDSPassword="$MYSQL_PASSWORD"

if [ "$ACTION" = "create" ]
  then
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" administrators
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" journal
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" analytics
fi

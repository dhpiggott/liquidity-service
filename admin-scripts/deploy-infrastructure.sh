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
       --stack-name liquidity-infrastructure-"$ENVIRONMENT"
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
ALB_LISTENER_CERTIFICATE=$(
  aws acm list-certificates \
    --region "$REGION" \
    --output text \
    --query \
      "CertificateSummaryList[?DomainName=='*.liquidityapp.com'].CertificateArn"
)

aws cloudformation "$ACTION"-stack \
  --region "$REGION" \
  --stack-name liquidity-infrastructure-"$ENVIRONMENT" \
  --template-body file://"$DIR"/../cfn-templates/liquidity-infrastructure.yaml \
  --parameters \
    ParameterKey=VPCId,ParameterValue="$VPC_ID" \
    ParameterKey=Subnets,ParameterValue=\""$SUBNETS"\" \
    ParameterKey=RDSUsername,ParameterValue="$MYSQL_USERNAME" \
    ParameterKey=RDSPassword,ParameterValue="$MYSQL_PASSWORD" \
    ParameterKey=ALBListenerCertificate,ParameterValue="$ALB_LISTENER_CERTIFICATE"

aws cloudformation wait stack-"$ACTION"-complete \
  --region "$REGION" \
  --stack-name liquidity-infrastructure-"$ENVIRONMENT"

if [ "$ACTION" = "create" ]
  then
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" administrators
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" journal
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" analytics
fi

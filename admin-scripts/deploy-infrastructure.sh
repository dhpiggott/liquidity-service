#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 region subdomain environment"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
SUBDOMAIN=$2
ENVIRONMENT=$3

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

NLB_LISTENER_CERTIFICATE=$(
  aws acm list-certificates \
    --region "$REGION" \
    --output text \
    --query \
      "CertificateSummaryList[?DomainName=='$SUBDOMAIN.liquidityapp.com'].CertificateArn"
)

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-infrastructure-"$ENVIRONMENT" \
  --template-file "$DIR"/../cfn-templates/liquidity-infrastructure.yaml \
  --no-fail-on-empty-changeset \
  --parameter-overrides \
      RDSUsername="$MYSQL_USERNAME" \
      RDSPassword="$MYSQL_PASSWORD" \
      NLBListenerCertificate="$NLB_LISTENER_CERTIFICATE"

if [ "$ACTION" = "create" ]
  then
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" administrators
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" journal
    "$DIR"/init-database.sh "$REGION" "$ENVIRONMENT" analytics
fi

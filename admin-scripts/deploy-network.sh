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
NLB_LISTENER_CERTIFICATE=$(
  aws acm list-certificates \
    --region "$REGION" \
    --output text \
    --query \
      "CertificateSummaryList[?DomainName=='*.liquidityapp.com'].CertificateArn"
)

aws cloudformation deploy \
  --region "$REGION" \
  --stack-name liquidity-network-"$ENVIRONMENT" \
  --template-file "$DIR"/../cfn-templates/liquidity-network.yaml \
  --no-fail-on-empty-changeset \
  --parameter-overrides \
      VPCId="$VPC_ID" \
      Subnets="$SUBNETS" \
      NLBListenerCertificate="$NLB_LISTENER_CERTIFICATE"

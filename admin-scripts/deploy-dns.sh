#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 <create|update> region environment subdomain"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

case $1 in
  create | update)
    ACTION=$1
    ;;
  *)
    echo "Usage: $0 <create|update> region environment subdomain"
    exit 1
    ;;
esac

REGION=$2
ENVIRONMENT=$3
SUBDOMAIN=$4

INFRASTRUCTURE_STACK=liquidity-infrastructure-$ENVIRONMENT

aws cloudformation $ACTION-stack \
  --region $REGION \
  --stack-name liquidity-dns-$SUBDOMAIN \
  --template-body file://$DIR/../cfn-templates/liquidity-dns.yaml \
  --parameters \
    ParameterKey=InfrastructureStack,ParameterValue=$INFRASTRUCTURE_STACK \
    ParameterKey=Subdomain,ParameterValue=$SUBDOMAIN

aws cloudformation wait stack-$ACTION-complete \
  --region $REGION \
  --stack-name liquidity-dns-$SUBDOMAIN

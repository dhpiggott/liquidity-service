#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 <create|update> region environment"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

case $1 in
  create | update)
    ACTION=$1
    ;;
  *)
    echo "Usage: $0 <create|update> region environment"
    exit 1
    ;;
esac

REGION=$2
ENVIRONMENT=$3

case $ENVIRONMENT in
  prod)
    STACK_SUFFIX=
    ;;
  *)
    STACK_SUFFIX=-$ENVIRONMENT
    ;;
esac

INFRASTRUCTURE_STACK=liquidity-infrastructure$STACK_SUFFIX

aws cloudformation $ACTION-stack \
  --region $REGION \
  --stack-name liquidity-dns$STACK_SUFFIX \
  --template-body file://$DIR/../cfn-templates/liquidity-dns.yaml \
  --parameters \
    ParameterKey=InfrastructureStack,ParameterValue=$INFRASTRUCTURE_STACK

aws cloudformation wait stack-$ACTION-complete \
  --region $REGION \
  --stack-name liquidity-dns$STACK_SUFFIX

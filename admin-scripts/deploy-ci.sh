#!/bin/bash

set -euo pipefail

if [ $# -ne 1 ]
  then
    echo "Usage: $0 <create|update>"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

case $1 in
  create | update)
    ACTION=$1
    ;;
  *)
    echo "Usage: $0 <create|update>"
    exit 1
    ;;
esac

aws cloudformation $ACTION-stack \
  --region eu-west-1 \
  --stack-name liquidity-ci \
  --template-body file://$DIR/../cfn-templates/liquidity-ci.yaml

aws cloudformation wait stack-$ACTION-complete \
  --region eu-west-1 \
  --stack-name liquidity-ci

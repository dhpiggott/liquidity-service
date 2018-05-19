#!/bin/bash

set -euo pipefail

if [ $# -ne 3 ]
  then
    echo "Usage: $0 region environment from"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

REGION=$1
ENVIRONMENT=$2
FROM=$3

RDS_HOSTNAME=$(
  aws cloudformation describe-stacks \
    --region $REGION \
    --stack-name liquidity-infrastructure-$ENVIRONMENT \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSHostname'].OutputValue"
)
RDS_USERNAME=$(
  aws cloudformation describe-stacks \
    --region $REGION \
    --stack-name liquidity-infrastructure-$ENVIRONMENT \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSUsername'].OutputValue"
)
RDS_PASSWORD=$(
  aws cloudformation describe-stacks \
    --region $REGION \
    --stack-name liquidity-infrastructure-$ENVIRONMENT \
    --output text \
    --query \
      "Stacks[?StackName=='liquidity-infrastructure-$ENVIRONMENT'] \
      | [0].Outputs[?OutputKey=='RDSPassword'].OutputValue"
)

docker run \
  --rm \
  --volume $DIR/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
  mysql:5 \
  mysql \
    --ssl-ca=/rds-combined-ca-bundle.pem \
    --ssl-mode=VERIFY_IDENTITY \
    --host=$RDS_HOSTNAME \
    --user=$RDS_USERNAME \
    --password=$RDS_PASSWORD \
    liquidity_analytics -e " \
      SELECT zones.zone_id, zones.created, zones.modified, zones.expires, \
      zones.metadata->'$.currency' AS currency, \
      LEFT(zone_name_changes.name, 20) \
      FROM zones \
      JOIN zone_name_changes \
      ON zone_name_changes.change_id = ( \
      SELECT zone_name_changes.change_id \
        FROM zone_name_changes \
        WHERE zone_name_changes.zone_id = zones.zone_id \
        AND zone_name_changes.name != 'Test' \
        ORDER BY change_id DESC LIMIT 1 \
      ) \
      WHERE zones.created >= '$FROM' \
      AND NOT zones.metadata->'$.isTest' IS true \
      ORDER BY created; \
    "

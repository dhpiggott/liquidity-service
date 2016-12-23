#!/bin/bash

set -euo pipefail

if [ -z "$1" ]
  then
    echo "Usage: $0 target"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

function finish {
    rm --force --recursive $DIR/image_stage
}
trap finish EXIT

mkdir $DIR/image_stage

(cd $DIR && sbt validate)
(cd $DIR && sbt liquidityServer/docker:stage liquidityAnalytics/docker:stage)

cp --recursive $DIR/server/target/docker/stage $DIR/image_stage/server
cp --recursive $DIR/analytics/target/docker/stage $DIR/image_stage/analytics
cp $DIR/docker-compose.yml $DIR/image_stage/
cp $DIR/load-data.sh $DIR/image_stage/
cp $DIR/save-data.sh $DIR/image_stage/

rsync --archive \
    --compress \
    --delete \
    --human-readable \
    --verbose \
    $DIR/image_stage/ $1/liquidity

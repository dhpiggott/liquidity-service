#!/bin/bash

set -euo pipefail

if [ -z "$1" ]
  then
    echo "Usage: $0 destination-hostname"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

function finish {
    rm -rf $DIR/stage
}
trap finish EXIT

mkdir -p $DIR/stage/legacyModels/stage
sbt liquidityLegacyModels/docker:stage
cp -r $DIR/legacyModels/target/docker/stage/* $DIR/stage/legacyModels/stage/
cp $DIR/legacyModels/run.sh $DIR/stage/legacyModels/

mkdir -p $DIR/stage/server
sbt liquidityServer/docker:stage
cp -r $DIR/server/target/docker/stage/* $DIR/stage/server/

mkdir -p $DIR/stage/analytics
sbt liquidityAnalytics/docker:stage
cp -r $DIR/analytics/target/docker/stage/* $DIR/stage/analytics/

cp $DIR/docker-compose.yml $DIR/stage/
cp $DIR/load-data.sh $DIR/stage/
cp $DIR/save-data.sh $DIR/stage/

rsync --archive \
    --compress \
    --delete \
    --human-readable -v \
    $DIR/stage/ $1:~/liquidity/

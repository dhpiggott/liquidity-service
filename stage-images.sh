#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 target"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

function finish {
    rm --force --recursive $DIR/image-stage
}
trap finish EXIT

mkdir $DIR/image-stage

(cd $DIR && sbt validate)
(cd $DIR && sbt liquidityServer/docker:stage)

cp --recursive $DIR/akka-cluster $DIR/image-stage/akka-cluster
cp --recursive $DIR/server/target/docker/stage $DIR/image-stage/server
cp $DIR/docker-compose.yml $DIR/image-stage/
cp $DIR/load-data.sh $DIR/image-stage/
cp $DIR/save-data.sh $DIR/image-stage/

rsync --archive \
    --human-readable \
    --delete \
    --delete-delay \
    --partial \
    --progress \
    --verbose \
    $DIR/image-stage/ $1/liquidity

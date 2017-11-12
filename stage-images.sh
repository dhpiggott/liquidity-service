#!/bin/bash

set -euo pipefail

if [ $# -ne 2 ]
  then
    echo "Usage: $0 target type"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

TARGET=$1
TYPE=$2

function finish {
    rm --force --recursive $DIR/image-stage
}
trap finish EXIT

mkdir $DIR/image-stage

(cd $DIR && sbt validate server/docker:stage)

cp --recursive $DIR/server/target/docker/stage $DIR/image-stage/server
cp $DIR/docker-compose.$TYPE.yml $DIR/image-stage/docker-compose.yml

rsync --archive \
    --human-readable \
    --delete \
    --delete-delay \
    --partial \
    --progress \
    --verbose \
    $DIR/image-stage/ $TARGET/liquidity

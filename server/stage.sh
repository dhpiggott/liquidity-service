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

mkdir $DIR/stage

(cd $DIR/../ && sbt liquidityServer/docker:stage)
cp -r $DIR/target/docker/stage $DIR/stage/liquidity

cp $DIR/docker-compose.yml $DIR/stage/
cp $DIR/save-data.sh $DIR/stage/
cp $DIR/load-data.sh $DIR/stage/

rsync --archive \
    --compress \
    --delete \
    --human-readable -v \
    $DIR/stage/ $1:~/liquidity/

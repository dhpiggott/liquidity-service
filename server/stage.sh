#!/bin/bash

set -e

if [ -z "$1" ]
  then
    echo "Usage: $0 destination-hostname"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
rm -rf $DIR/stage && mkdir $DIR/stage

(cd $DIR/../ && sbt server/docker:stage)
cp -r $DIR/target/docker/stage $DIR/stage/liquidity
cp -r $DIR/nginx $DIR/stage/nginx

cp docker-compose.yml $DIR/stage/
cp save-data.sh $DIR/stage/
cp load-data.sh $DIR/stage/

rsync --archive \
    --compress \
    --delete \
    --human-readable -v \
    $DIR/stage/ $1:~/liquidity/

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

(cd $DIR && sbt validate server/docker:stage)

cp --recursive $DIR/server/target/docker/stage $DIR/image-stage/server
cp $DIR/docker-compose.yml $DIR/image-stage/
cp $DIR/docker-compose-ec2.yml $DIR/image-stage/docker-compose.override.yml
cp $DIR/journal.sql $DIR/image-stage/
cp $DIR/init-journal.sh $DIR/image-stage/
cp $DIR/analytics.sql $DIR/image-stage/
cp $DIR/init-analytics.sh $DIR/image-stage/
cp $DIR/save-journal.sh $DIR/image-stage/
cp $DIR/load-journal.sh $DIR/image-stage/

rsync --archive \
    --human-readable \
    --delete \
    --delete-delay \
    --partial \
    --progress \
    --verbose \
    $DIR/image-stage/ $1/liquidity

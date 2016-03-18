#!/bin/bash

set -e

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
rm -rf $DIR/stage && mkdir $DIR/stage

cp cassandra.sh $DIR/stage/
(cd $DIR && ./activator docker:stage)
cp -r $DIR/target/docker/stage $DIR/stage/liquidity
cp liquidity.sh $DIR/stage/
cp -r $DIR/nginx $DIR/stage/nginx
cp nginx.sh $DIR/stage/

cp save-data.sh $DIR/stage/
cp load-data.sh $DIR/stage/

docker rm -f -v nginx liquidity || true
(cd $DIR/stage && ./liquidity.sh && ./nginx.sh)

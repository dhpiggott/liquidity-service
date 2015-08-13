#!/bin/bash

set -e

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

(cd $DIR && ./activator docker:stage)

sudo docker build -t dhpcs/liquidity.dhpcs.com $DIR/target/docker/stage/
sudo docker run -d \
    --restart always \
    --name liquidity \
    --link cassandra:cassandra \
    dhpcs/liquidity.dhpcs.com

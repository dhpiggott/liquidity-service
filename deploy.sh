#!/bin/bash

set -e

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

(cd $DIR && ./activator docker:stage)

sudo docker build -t dhpcs/liquidity.dhpcs.com $DIR/target/docker/docker/stage/
sudo docker run -d \
    --restart always \
    -e VIRTUAL_HOST=liquidity.dhpcs.com \
    -e DISABLE_OCSP=true \
    -e REQUEST_CLIENT_CERT=true \
    dhpcs/liquidity.dhpcs.com

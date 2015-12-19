#!/bin/bash

set -e

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
docker build -t dhpcs/liquidity.dhpcs.com $DIR/liquidity
docker run -d \
    --restart always \
    --name liquidity \
    --link cassandra:cassandra \
    dhpcs/liquidity.dhpcs.com

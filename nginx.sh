#!/bin/bash

set -e

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
sudo docker run -d -p 443:443 \
    --restart always \
    --name nginx \
    -v $DIR/nginx/dh2048.pem:/etc/nginx/certs/dh2048.pem:ro \
    -v $DIR/nginx/liquidity.dhpcs.com.crt:/etc/nginx/certs/liquidity.dhpcs.com.crt:ro \
    -v $DIR/nginx/liquidity.dhpcs.com.key:/etc/nginx/certs/liquidity.dhpcs.com.key:ro \
    -v $DIR/nginx/default.conf:/etc/nginx/conf.d/default.conf:ro \
    --link liquidity:liquidity \
    nginx:1

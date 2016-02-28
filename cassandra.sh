#!/bin/bash

set -e

docker run -d \
    --restart always \
    --name cassandra \
    cassandra:3

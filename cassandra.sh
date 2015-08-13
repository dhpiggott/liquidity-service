#!/bin/bash

set -e

sudo docker run -d \
    --restart always \
    --name cassandra \
    cassandra:2

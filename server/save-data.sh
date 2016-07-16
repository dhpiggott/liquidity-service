#!/bin/bash

set -e

if [ -z "$1" ]
  then
    echo "Usage: $0 output-directory"
    exit 1
fi

mkdir -p $1
touch $1/schema.cql
docker run --rm \
    -v $1/schema.cql:/mnt/export \
    --net=liquidity_default \
    --link liquidity_cassandra_1:cassandra \
    cassandra:3 sh -c 'exec cqlsh -e "DESCRIBE KEYSPACE akka;" cassandra > /mnt/export'
touch $1/messages.csv
docker run --rm \
    -v $1/messages.csv:/mnt/export \
    --net=liquidity_default \
    --link liquidity_cassandra_1:cassandra \
    cassandra:3 sh -c 'exec cqlsh -e "COPY akka.messages TO '\''/mnt/export'\'';" cassandra'

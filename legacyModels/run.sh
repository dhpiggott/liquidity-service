#!/bin/bash

set -euo pipefail

if [ -z "$1" ]
  then
    echo "Usage: $0 working-directory"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

docker build --tag \
    liquidity_legacy_models $DIR/stage

docker run --rm \
    --net=liquidity_default \
    --link liquidity_cassandra_1:cassandra \
    liquidity_legacy_models

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
    cassandra:3 sh -c 'exec cqlsh -e "COPY akka_rewrite.messages TO '\''/mnt/export'\'' WITH NULL='\''null'\'';" cassandra'

docker run --rm \
    --net=liquidity_default \
    --link liquidity_cassandra_1:cassandra \
    cassandra:3 sh -c 'exec cqlsh -e "DROP KEYSPACE IF EXISTS akka;" cassandra'
docker run --rm \
    -v $1/schema.cql:/mnt/import \
    --net=liquidity_default \
    --link liquidity_cassandra_1:cassandra \
    cassandra:3 sh -c 'exec cqlsh -f /mnt/import cassandra'
rm $1/schema.cql
docker run --rm \
    -v $1/messages.csv:/mnt/import \
    --net=liquidity_default \
    --link liquidity_cassandra_1:cassandra \
    cassandra:3 sh -c 'exec cqlsh -e "COPY akka.messages FROM '\''/mnt/import'\'' WITH NULL='\''null'\'';" cassandra'
rm $1/messages.csv

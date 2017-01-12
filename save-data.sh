#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 output-directory"
    exit 1
fi

mkdir --parents $1

touch $1/schema.cql
docker run --rm \
    --volume $1/schema.cql:/mnt/export \
    --net=liquidity_default \
    --link liquidity_cassandra_1:cassandra \
    cassandra:3 sh -c 'exec cqlsh -e "DESCRIBE KEYSPACE liquidity_server;" cassandra > /mnt/export'

for table in $(docker run --rm \
                   --net=liquidity_default \
                   --link liquidity_cassandra_1:cassandra \
                   cassandra:3 sh -c 'exec cqlsh -e "USE liquidity_server; DESCRIBE TABLES;" cassandra')
do
    touch $1/$table.csv
    docker run --rm \
        --volume $1/$table.csv:/mnt/export \
        --net=liquidity_default \
        --link liquidity_cassandra_1:cassandra \
        cassandra:3 sh -c 'exec cqlsh -e "COPY liquidity_server.'$table' TO '\''/mnt/export'\'' WITH NULL='\''null'\'';" cassandra'
done

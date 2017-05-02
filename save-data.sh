#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 output-directory"
    exit 1
fi

mkdir --parents $1

for keyspace in "liquidity_server_v2"
do
    touch $1/$keyspace.schema.cql
    docker run --rm \
        --volume $1/$keyspace.schema.cql:/mnt/export \
        --net=liquidity_default \
        --link liquidity_cassandra_1:cassandra \
        cassandra:3 sh -c 'exec cqlsh -e "DESCRIBE KEYSPACE '$keyspace';" cassandra > /mnt/export'
    for table in $(docker run --rm \
                       --net=liquidity_default \
                       --link liquidity_cassandra_1:cassandra \
                       cassandra:3 sh -c 'exec cqlsh -e "USE '$keyspace'; DESCRIBE TABLES;" cassandra')
    do
        touch $1/$keyspace.$table.csv
        docker run --rm \
            --volume $1/$keyspace.$table.csv:/mnt/export \
            --net=liquidity_default \
            --link liquidity_cassandra_1:cassandra \
            cassandra:3 sh -c 'exec cqlsh -e "COPY '$keyspace'.'$table' TO '\''/mnt/export'\'' WITH NULL='\''null'\'';" cassandra'
    done
done

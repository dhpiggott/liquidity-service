#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 input-directory"
    exit 1
fi

for keyspace in "liquidity_server_v2"
do
    docker run --rm \
        --volume $1/$keyspace.schema.cql:/mnt/import \
        --net=liquidity_default \
        --link liquidity_cassandra_1:cassandra \
        cassandra:3 sh -c 'exec cqlsh -f /mnt/import cassandra'
    for table in $(docker run --rm \
                       --net=liquidity_default \
                       --link liquidity_cassandra_1:cassandra \
                       cassandra:3 sh -c 'exec cqlsh -e "USE '$keyspace'; DESCRIBE TABLES;" cassandra')
    do
        docker run --rm \
            --volume $1/$keyspace.$table.csv:/mnt/import \
            --net=liquidity_default \
            --link liquidity_cassandra_1:cassandra \
            cassandra:3 sh -c 'exec cqlsh -e "COPY '$keyspace'.'$table' FROM '\''/mnt/import'\'' WITH NULL='\''null'\'';" cassandra'
    done
done

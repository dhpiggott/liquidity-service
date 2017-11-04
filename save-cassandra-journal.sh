#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 output-directory"
    exit 1
fi

mkdir --parents $1

for keyspace in "liquidity_journal_v4" "liquidity_snapshot_store_v4"
do
    touch $1/$keyspace.schema.cql
    docker-compose run -T --rm \
        --volume $1/$keyspace.schema.cql:/mnt/export \
        cassandra sh -c 'exec cqlsh -e "DESCRIBE KEYSPACE '$keyspace';" cassandra > /mnt/export'
    for table in $(docker-compose run -T --rm \
                       cassandra sh -c 'exec cqlsh -e "USE '$keyspace'; DESCRIBE TABLES;" cassandra')
    do
        touch $1/$keyspace.$table.csv
        docker-compose run -T --rm \
            --volume $1/$keyspace.$table.csv:/mnt/export \
            cassandra sh -c 'exec cqlsh -e "COPY '$keyspace'.'$table' TO '\''/mnt/export'\'' WITH NULL='\''null'\'';" cassandra'
    done
done

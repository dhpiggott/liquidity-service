#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 input-directory"
    exit 1
fi

for keyspace in "liquidity_journal_v4" "liquidity_snapshot_store_v4"
do
    docker-compose run -T --rm \
        --volume $1/$keyspace.schema.cql:/mnt/import \
        cassandra sh -c 'exec cqlsh -f /mnt/import cassandra'
    for table in $(docker-compose run -T --rm \
                       cassandra sh -c 'exec cqlsh -e "USE '$keyspace'; DESCRIBE TABLES;" cassandra')
    do
        docker-compose run -T --rm \
            --volume $1/$keyspace.$table.csv:/mnt/import \
            cassandra sh -c 'exec cqlsh -e "COPY '$keyspace'.'$table' FROM '\''/mnt/import'\'' WITH NULL='\''null'\'';" cassandra'
    done
done

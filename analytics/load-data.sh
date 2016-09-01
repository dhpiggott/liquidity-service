#!/bin/bash

set -euo pipefail

if [ -z "$1" ]
  then
    echo "Usage: $0 input-directory"
    exit 1
fi

docker run --rm \
    --link cassandra \
    cassandra:3 sh -c 'exec cqlsh -e "DROP KEYSPACE IF EXISTS akka;" cassandra'
docker run --rm \
    -v $1/schema.cql:/mnt/import \
    --link cassandra \
    cassandra:3 sh -c 'exec cqlsh -f /mnt/import cassandra'
docker run --rm \
    -v $1/messages.csv:/mnt/import \
    --link cassandra \
    cassandra:3 sh -c 'exec cqlsh -e "COPY akka.messages FROM '\''/mnt/import'\'' WITH NULL='\''null'\'';" cassandra'

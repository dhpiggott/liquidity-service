#!/bin/bash

set -e

if [ -z "$1" ]
  then
    echo "Usage: $0 input-directory"
    exit 1
fi

sudo docker run --rm \
    --link cassandra:cassandra \
    cassandra:2 sh -c 'exec cqlsh -e "DROP KEYSPACE IF EXISTS akka;" cassandra'
sudo docker run --rm \
    -v $1/schema.cql:/mnt/import \
    --link cassandra:cassandra \
    cassandra:2 sh -c 'exec cqlsh -f /mnt/import cassandra'
sudo docker run --rm \
    -v $1/messages.csv:/mnt/import \
    --link cassandra:cassandra \
    cassandra:2 sh -c 'exec cqlsh -e "COPY akka.messages FROM '\''/mnt/import'\'';" cassandra'

#!/bin/bash

set -e

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
rm -rf $DIR/migration && mkdir -p $DIR/migration/v2

docker rm -f -v nginx liquidity

touch $DIR/migration/v2/schema.cql
docker run --rm \
    -v $DIR/migration/v2/schema.cql:/mnt/export \
    --link cassandra:cassandra \
    cassandra:2 sh -c 'exec cqlsh -e "DESCRIBE KEYSPACE akka;" cassandra > /mnt/export'
touch $DIR/migration/v2/messages.csv
docker run --rm \
    -v $DIR/migration/v2/messages.csv:/mnt/export \
    --link cassandra:cassandra \
    cassandra:2 sh -c 'exec cqlsh -e "COPY akka.messages TO '\''/mnt/export'\'' WITH NUMPROCESSES=1 AND CHUNKSIZE=1 AND MAXREQUESTS=1;" cassandra'
docker rm -f -v cassandra
docker create \
    --name cassandra-data \
    -v /var/lib/cassandra \
    cassandra:2 \
    /bin/true
docker run -d \
    --restart always \
    --name cassandra \
    --volumes-from cassandra-data \
    cassandra:2
sleep 20

docker run --rm \
    -v $DIR/migration/v2/schema.cql:/mnt/import \
    --link cassandra:cassandra \
    cassandra:2 sh -c 'exec cqlsh -f /mnt/import cassandra'
docker run --rm \
    -v $DIR/migration/v2/messages.csv:/mnt/import \
    --link cassandra:cassandra \
    cassandra:2 sh -c 'exec cqlsh -e "COPY akka.messages FROM '\''/mnt/import'\'' WITH NUMPROCESSES=1 AND CHUNKSIZE=1 AND MAXBATCHSIZE=1;" cassandra'
docker exec cassandra nodetool upgradesstables
docker exec cassandra nodetool drain
docker rm -f -v cassandra
docker run -d \
    --restart always \
    --name cassandra \
    --volumes-from cassandra-data \
    cassandra:3
sleep 20

docker exec cassandra nodetool upgradesstables
$DIR/save-data.sh $DIR/migration/v3
docker rm -f -v cassandra cassandra-data

$DIR/cassandra.sh
sleep 20

$DIR/load-data.sh $DIR/migration/v3

$DIR/liquidity.sh && $DIR/nginx.sh

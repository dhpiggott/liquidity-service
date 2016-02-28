#!/bin/bash

set -e

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
rm -rf $DIR/migration && mkdir -p $DIR/migration/v0.6 && mkdir -p $DIR/migration/v0.7

docker pull cassandra:2

docker rm -f -v nginx liquidity
$DIR/save-data.sh $DIR/migration/v0.6

docker rm -f -v cassandra
$DIR/cassandra.sh
sleep 20

cp $DIR/v0.7-schema.cql $DIR/migration/v0.7/schema.cql
awk '{print $1,$2,$3,"fada7318-dec4-11e5-92b1-c8f733906f0a","20160229",$4,F,F,F,$5}' FS=, OFS=, F='' $DIR/migration/v0.6/messages.csv > $DIR/migration/v0.7/messages.csv

$DIR/load-data.sh $DIR/migration/v0.7
$DIR/liquidity.sh && $DIR/nginx.sh

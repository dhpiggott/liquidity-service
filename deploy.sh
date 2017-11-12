#!/bin/bash

set -euo pipefail

if [ $# -ne 1 ]
  then
    echo "Usage: $0 tag"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

eval $(aws ecr get-login --no-include-email --region eu-west-2)

(cd $DIR && echo "TAG=$1" > .env && \
    docker-compose -f docker-compose.ec2.yml pull && \
    docker-compose -f docker-compose.ec2.yml up -d)

#!/bin/bash

set -euo pipefail

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

eval $(aws ecr get-login --no-include-email --region eu-west-2)

(cd $DIR && sbt validate server/docker:publish)

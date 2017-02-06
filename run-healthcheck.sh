#!/bin/bash

set -euo pipefail

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

$DIR/healthcheck/target/universal/stage/bin/liquidity-healthcheck "$@"

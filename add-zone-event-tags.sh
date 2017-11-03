#!/bin/bash

set -euo pipefail

if [ $# -eq 0 ]
  then
    echo "Usage: $0 target-directory"
    exit 1
fi

gawk -i inplace -F ',' -v OFS=',' '{ if ($14 == "null") $14 = "zone-event"; print}' $1/liquidity_journal_v4.messages.csv


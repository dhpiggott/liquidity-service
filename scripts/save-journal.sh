#!/bin/bash

set -euo pipefail

if [ $# -ne 4 ]
  then
    echo "Usage: $0 output-directory host username password"
    exit 1
fi

DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

mkdir --parents $1
touch $1/journal_dump.sql

docker run --rm \
	--volume $DIR/rds-combined-ca-bundle.pem:/rds-combined-ca-bundle.pem \
    --volume $1/journal_dump.sql:/dump.sql \
    mysql:5 \
	sh -c "mysqldump \
		--ssl-ca=/rds-combined-ca-bundle.pem \
		--ssl-mode=VERIFY_IDENTITY \
		--host=$2 \
		--user=$3 \
		--password=$4 \
        --skip-opt \
        --no-create-info \
        --add-locks \
        --disable-keys \
        --extended-insert \
        --quick liquidity_journal > /dump.sql"

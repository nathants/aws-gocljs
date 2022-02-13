#!/bin/bash
set -eou pipefail

source env.sh

seen=$(mktemp)
trap "rm -f $seen || true" EXIT

while true; do
    cli-aws s3-ls -r $PROJECT_BUCKET/logs/$(date --utc --date="1 minute ago" +%s) | awk '{print $4}' | while read log; do
        if ! grep $log $seen &>/dev/null; then
            cli-aws s3-get s3://$PROJECT_BUCKET/$log
            echo $log >> $seen
        fi
    done
    sleep 1
done

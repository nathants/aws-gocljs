#!/bin/bash
set -eou pipefail

source env.sh

touch frontend/public/index.html.gz
touch frontend/public/favicon.png

libaws infra-ensure infra.yaml --preview | sed 's/^/libaws: /'

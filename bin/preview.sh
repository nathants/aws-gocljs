#!/bin/bash
set -eou pipefail

source ${1:-env.sh}

touch frontend/public/index.html.gz
touch frontend/public/favicon.png

libaws infra-ensure infra.yaml --preview | sed 's/^/libaws: /'

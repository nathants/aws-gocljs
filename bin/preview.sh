#!/bin/bash
set -eou pipefail

source env.sh

touch frontend/public/index.html.gz

libaws infra-ensure infra.yaml --preview | sed 's/^/libaws: /'

rm frontend/public/index.html.gz

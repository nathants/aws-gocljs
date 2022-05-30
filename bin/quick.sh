#!/bin/bash
set -eou pipefail

source env.sh

touch frontend/public/index.html.gz
touch frontend/public/favicon.png

time libaws infra-ensure infra.yaml --quick ${PROJECT_NAME}

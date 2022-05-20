#!/bin/bash
set -eou pipefail

source env.sh

echo rebuild ${PROJECT_NAME}

time libaws infra-ensure infra.yaml --quick ${PROJECT_NAME}

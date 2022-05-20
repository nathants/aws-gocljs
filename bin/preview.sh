#!/bin/bash
set -eou pipefail

source env.sh

libaws infra-ensure infra.yaml --preview | sed 's/^/libaws: /'

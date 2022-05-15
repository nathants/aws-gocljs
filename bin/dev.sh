#!/bin/bash
set -eou pipefail

source env.sh

(
    cd frontend
    npm ci
    npx shadow-cljs watch app
) 2>&1 | sed 's/^/shadow-cljs: /' &

pid=$!
trap "kill $pid &>/dev/null || true" EXIT

find -type f | grep -F -v -e .shadow-cljs -e backups -e node_modules | entr -r libaws infra-ensure infra.yaml --quick ${PROJECT_NAME} 2>&1 | sed 's/^/libaws: /'

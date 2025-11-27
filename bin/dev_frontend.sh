#!/bin/bash
set -eou pipefail

source ${1:-env.sh}

cd frontend
pnpm install --frozen-lockfile
pnpx shadow-cljs watch app

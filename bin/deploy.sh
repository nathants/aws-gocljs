#!/bin/bash
set -eou pipefail

source env.sh

(ps -ef | grep shadow-cljs | awk '{print $2}' | xargs kill) &>/dev/null || true

(
    cd frontend
    npm ci
    npx shadow-cljs release app
) 2>&1 | sed 's/^/shadow-cljs: /'

# inline js into html then gzip
temp=$(mktemp)
cat frontend/public/index.html | grep script -B100 | grep -v script >> $temp
echo '<script type="text/javascript" charset="utf-8">' >> $temp
cat frontend/public/js/main.js >> $temp
echo '</script>' >> $temp
cat frontend/public/index.html | grep script -A100 | grep -v script >> $temp
cat $temp | gzip --best > frontend/public/index.html.gzip
rm $temp

cli-aws lambda-ensure backend/*.go 2>&1 | sed 's/^/cli-aws: /'

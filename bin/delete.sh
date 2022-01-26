#!/bin/bash
set -eou pipefail

cli-aws lambda-rm --everything backend/*.go 2>&1 | sed 's/^/cli-aws: /'

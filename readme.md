# new-gocljs

## why

web dev should be easier.

## what

demo: https://gocljs.nathants.com

a template for a web project with an aws [infrastructure set](https://github.com/nathants/libaws#infrastructure-set) containing:
 - go backend on lambda
 - api lambda trigger
 - websocket lambda trigger
 - s3 and dynamodb for state
 - cljs frontend with reagent
 - dead simple operations
 - fast development cycle
 - fast deploy cycle

## dependencies

use the included [Dockerfile](./Dockerfile) or install the following dependencies:
- npm
- jdk
- go
- bash
- [entr](https://formulae.brew.sh/formula/entr)

## aws prerequisites

- aws [route53](https://console.aws.amazon.com/route53/v2/hostedzones) has the domain or its parent from env.sh

- aws [acm](https://us-west-2.console.aws.amazon.com/acm/home) has a wildcard cert for the domain or its parent from env.sh

## install

```bash
go install github.com/nathants/libaws@latest

export PATH=$PATH:$(go env GOPATH)/bin
```

```bash
git clone https://github.com/nathants/new-gocljs
cd new-gocljs
cp env.sh.template env.sh # update values
bash bin/check.sh         # lint
bash bin/deploy.sh        # ensure aws infra and deploy prod release
bash bin/dev.sh           # rapidly iterate on lambda backend and localhost:8000 frontend
bash bin/logs.sh          # tail the logs
bash bin/delete.sh        # delete
```

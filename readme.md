# new-gocljs

## why

web dev should be easier.

## what

a template for a project with:
 - go backend on lambda with s3 and dynamodb
 - cljs frontend with reagent
 - dead simple operations
 - fast development cycle
 - fast deploy cycle

## dependencies

- npm
- jdk
- go
- bash
- [entr](https://formulae.brew.sh/formula/entr)

## prerequisites

required because of `// trigger: api dns=${PROJECT_DOMAIN}` in backend/backend.go:
- aws [route53](https://console.aws.amazon.com/route53/v2/hostedzones) has the domain or its parent from env.sh
- aws [acm](https://us-west-2.console.aws.amazon.com/acm/home) has a regional cert for the domain or a wildcard cert for its parent from env.sh

## alternative

to drop the dependency on route53 and acm change trigger to:
- `// trigger: api`

then find apigateway url with:
- `cli-aws lambda-api backend/backend.go`

## install

`go install github.com/nathants/cli-aws@latest`

```
git clone https://github.com/nathants/new-gocljs
cd new-gocljs
cp env.sh.template env.sh # update values
bash bin/check.sh         # lint
bash bin/deploy.sh        # ensure aws infra and deploy prod release
bash bin/dev.sh           # rapidly iterate by updating the lambda zip
bash bin/logs.sh          # tail the logs
bash bin/delete.sh        # delete
```

## [demo](https://gocljs.nathants.com)

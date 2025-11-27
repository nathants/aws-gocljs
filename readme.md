# AWS-GOCLJS

## Why

So you can just ship fullstack web on AWS.

![](https://github.com/nathants/aws-gocljs/raw/master/screenshot.png)

## How

Start with working implementations of everything, then [iterate](#demo).

Fast and reliable [automation](https://github.com/nathants/aws-gocljs/tree/master/bin).

Easy browser [testing](https://github.com/nathants/py-webengine).

The tightest possible security [policy](https://github.com/nathants/aws-gocljs/blob/master/bin/ensure.sh), no other JS can ever run.

## What

A project scaffold for a fullstack webapp on AWS with an [infrastructure set](https://github.com/nathants/aws-gocljs/tree/master/infra.yaml) ready-to-deploy with [libaws](https://github.com/nathants/libaws).

The project scaffold contains:
 - A Go Lambda backend.
 - A ClojureScript [React](http://reagent-project.github.io/) frontend.
 - S3 and DynamoDB for state.
 - HTTP and WebSocket APIs.
 - [Logging](https://github.com/nathants/aws-gocljs/tree/master/bin/logs.sh).
 - [DevOps](https://github.com/nathants/aws-gocljs/tree/master/bin).

A live demo is [here](https://gocljs.nathants.com).

## Lambda Zip

The Lambda zip contains only 3 files:

```bash
>> ls -lh | awk '{print $9, $5}' | column -t

favicon.png    2.7K # favicon
index.html.gz  296K # web app
main           15M  # go binary
```

The index.html.gz:

```html
<!DOCTYPE html>
<html>
    <head>
      <meta charset="utf-8">
      <meta http-equiv="Content-Security-Policy" content="script-src 'sha256-${JS_SHA256}'">
      <link rel="icon" href="/favicon.png">
    </head>
    <body>
        <div id="app"></div>
        <script type="text/javascript">
          ${JS}
        </script>
    </body>
</html>
```

The lambda zip itself:

```bash
>> ls -lh | awk '{print $9, $5}'
lambda.zip 4.6M
```

## Demo

![](https://github.com/nathants/aws-gocljs/raw/master/demo.gif)

## Dependencies

Use the included [Dockerfile](./Dockerfile) or install the following dependencies:
- pnpm
- jdk
- go
- bash
- [entr](https://formulae.brew.sh/formula/entr)
- [libaws](https://github.com/nathants/libaws)

## AWS Prerequisites

- AWS [Route53](https://console.aws.amazon.com/route53/v2/hostedzones) has the domain or its parent from env.sh

- AWS [Acm](https://us-west-2.console.aws.amazon.com/acm/home) has a wildcard cert for the domain or its parent from env.sh

## Install

```bash
git clone https://github.com/nathants/aws-gocljs
cd aws-gocljs
cp env.sh.template env.sh # update values
```

## Usage

```bash
bash bin/check.sh         # lint
bash bin/preview.sh       # preview changes to aws infra
bash bin/ensure.sh        # ensure aws infra
bash bin/dev.sh           # iterate on backend and frontend
bash bin/logs.sh          # tail the logs
bash bin/delete.sh        # delete aws infra
```

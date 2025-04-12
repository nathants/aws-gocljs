# aws-gocljs

## why

so you can just ship fullstack web on aws.

## how

start with working implementations of everything, then [iterate](#demo).

fast and reliable [automation](https://github.com/nathants/aws-gocljs/tree/master/bin).

easy browser [testing](https://github.com/nathants/py-webengine).

the tightest possible security [policy](https://github.com/nathants/aws-gocljs/blob/master/bin/ensure.sh), no other js can ever run.

## what

a project scaffold for a fullstack webapp on aws with an [infrastructure set](https://github.com/nathants/aws-gocljs/tree/master/infra.yaml) ready-to-deploy with [libaws](https://github.com/nathants/libaws).

the project scaffold contains:
 - a go lambda backend.
 - a clojurescript [react](http://reagent-project.github.io/) frontend.
 - s3 and dynamodb for state.
 - http and websocket apis.
 - [logging](https://github.com/nathants/aws-gocljs/tree/master/bin/logs.sh).
 - [devops](https://github.com/nathants/aws-gocljs/tree/master/bin).

a live demo is [here](https://gocljs.nathants.com).

## lambda zip

the lambda zip contains only 3 files:

```bash
>> ls -lh | awk '{print $9, $5}' | column -t

favicon.png    2.7K # favicon
index.html.gz  296K # web app
main           15M  # go binary
```

the index.html.gz:

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

the lambda zip itself:

```bash
>> ls -lh | awk '{print $9, $5}'
lambda.zip 4.6M
```

## demo

![](https://github.com/nathants/aws-gocljs/raw/master/demo.gif)

## dependencies

use the included [Dockerfile](./Dockerfile) or install the following dependencies:
- npm
- jdk
- go
- bash
- [entr](https://formulae.brew.sh/formula/entr)
- [libaws](https://github.com/nathants/libaws)

## aws prerequisites

- aws [route53](https://console.aws.amazon.com/route53/v2/hostedzones) has the domain or its parent from env.sh

- aws [acm](https://us-west-2.console.aws.amazon.com/acm/home) has a wildcard cert for the domain or its parent from env.sh

## install

```bash
git clone https://github.com/nathants/aws-gocljs
cd aws-gocljs
cp env.sh.template env.sh # update values
```

## usage

```bash
bash bin/check.sh         # lint
bash bin/preview.sh       # preview changes to aws infra
bash bin/ensure.sh        # ensure aws infra
bash bin/dev.sh           # iterate on backend and frontend
bash bin/logs.sh          # tail the logs
bash bin/delete.sh        # delete aws infra
```

//
// attr: name ${PROJECT_NAME}
// attr: concurrency 0
// attr: memory 128
// attr: timeout 60
//
// dynamodb: ${PROJECT_NAME} id:s:hash
// s3: ${PROJECT_BUCKET} cors=true acl=private ttldays=14
//
// trigger: api dns=${PROJECT_DOMAIN}
// trigger: cloudwatch rate(5 minutes)
//
// policy: AWSLambdaBasicExecutionRole
// allow: dynamodb:* arn:aws:dynamodb:*:*:table/${PROJECT_NAME}
// allow: s3:* arn:aws:s3:::${PROJECT_BUCKET}/*
//
// include: ../frontend/public/index.html.gzip
// include: ../frontend/public/favicon.png
//

package main

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"mime"
	"net/http"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/service/s3"
	"github.com/mitchellh/mapstructure"
	"github.com/nathants/cli-aws/lib"
	uuid "github.com/satori/go.uuid"
)

func corsHeaders() map[string]string {
	return map[string]string{
		"Access-Control-Allow-Origin":  "*",
		"Access-Control-Allow-Methods": "POST, GET, OPTIONS, PUT, DELETE",
		"Access-Control-Allow-Headers": "auth, content-type",
	}
}

func index() events.APIGatewayProxyResponse {
	headers := map[string]string{
		"Content-Type": "text/html; charset=UTF-8",
	}
	indexBytes, err := ioutil.ReadFile("frontend/public/index.html.gzip")
	if err == nil {
		headers["Content-Encoding"] = "gzip"
	} else {
		indexBytes, err = ioutil.ReadFile("frontend/public/index.html")
		if err != nil {
			panic(err)
		}
	}
	return events.APIGatewayProxyResponse{
		Body:            base64.StdEncoding.EncodeToString(indexBytes),
		IsBase64Encoded: true,
		StatusCode:      200,
		Headers:         headers,
	}
}

func static(path string) events.APIGatewayProxyResponse {
	data, err := ioutil.ReadFile("frontend/public" + path)
	if err != nil {
		return events.APIGatewayProxyResponse{
			StatusCode: 404,
		}
	}
	headers := map[string]string{
		"Content-Type": mime.TypeByExtension("." + last(strings.Split(path, "."))),
	}
	var body string
	if len(data) > 4*1024*1024 {
		var buf bytes.Buffer
		w := gzip.NewWriter(&buf)
		_, err = w.Write(data)
		if err != nil {
			panic(err)
		}
		err = w.Close()
		if err != nil {
			panic(err)
		}
		body = base64.StdEncoding.EncodeToString(buf.Bytes())
		headers["Content-Encoding"] = "gzip"
	} else {
		body = base64.StdEncoding.EncodeToString(data)
	}
	return events.APIGatewayProxyResponse{
		Body:            body,
		IsBase64Encoded: true,
		StatusCode:      200,
		Headers:         headers,
	}
}

func last(xs []string) string {
	return xs[len(xs)-1]
}

func notfound() events.APIGatewayProxyResponse {
	return events.APIGatewayProxyResponse{
		Body:       "404",
		StatusCode: 404,
	}
}

func handleApiEvent(ctx context.Context, event *events.APIGatewayProxyRequest, res chan<- events.APIGatewayProxyResponse) {
	if strings.HasPrefix(event.Path, "/js/main.js") ||
		strings.HasPrefix(event.Path, "/favicon.") {
		res <- static(event.Path)
		return
	}
	if strings.HasPrefix(event.Path, "/api/") {
		if event.HTTPMethod == http.MethodOptions {
			res <- events.APIGatewayProxyResponse{
				StatusCode: 200,
				Headers:    corsHeaders(),
			}
			return
		}
		switch event.Path {
		case "/api/time":
			switch event.HTTPMethod {
			case http.MethodGet:
				httpTimeGet(ctx, event, res)
				return
			}
		default:
		}
	}
	res <- index()
}

type timeGetReponse struct {
	Time int `json:"time"`
}

func httpTimeGet(_ context.Context, event *events.APIGatewayProxyRequest, res chan<- events.APIGatewayProxyResponse) {
	resp := timeGetReponse{
		Time: int(time.Now().UTC().Unix()),
	}
	data, err := json.Marshal(resp)
	if err != nil {
		panic(err)
	}
	res <- events.APIGatewayProxyResponse{
		StatusCode: 200,
		Headers:    corsHeaders(),
		Body:       string(data),
	}
}

func logRecover(r interface{}, res chan<- events.APIGatewayProxyResponse) {
	stack := string(debug.Stack())
	lib.Logger.Println(r)
	lib.Logger.Println(stack)
	res <- events.APIGatewayProxyResponse{
		StatusCode: 500,
		Body:       fmt.Sprint(r) + "\n" + stack,
	}
}

func handle(ctx context.Context, event map[string]interface{}, res chan<- events.APIGatewayProxyResponse) {
	defer func() {
		if r := recover(); r != nil {
			logRecover(r, res)
		}
	}()
	_, ok := event["path"]
	if !ok {
		res <- notfound()
		return
	}
	apiEvent := &events.APIGatewayProxyRequest{}
	err := mapstructure.Decode(event, apiEvent)
	if err != nil {
		panic(err)
	}
	handleApiEvent(ctx, apiEvent, res)
}

func handleRequest(ctx context.Context, event map[string]interface{}) (events.APIGatewayProxyResponse, error) {
	setupLogging(ctx)
	defer lib.Logger.Flush()
	start := time.Now()
	res := make(chan events.APIGatewayProxyResponse)
	go handle(ctx, event, res)
	r := <-res
	path, ok := event["path"]
	ts := time.Now().UTC().Format(time.RFC3339)
	if ok {
		ip := event["requestContext"].(map[string]interface{})["identity"].(map[string]interface{})["sourceIp"].(string)
		lib.Logger.Println("http", r.StatusCode, path, time.Since(start), ip, ts)
	} else {
		lib.Logger.Println("async", time.Since(start), ts)
	}
	return r, nil
}

func setupLogging(ctx context.Context) {
	lock := sync.RWMutex{}
	var lines []string
	uid := uuid.NewV4().String()
	count := 0
	lib.Logger = &lib.LoggerStruct{
		Print: func(args ...interface{}) {
			lock.Lock()
			defer lock.Unlock()
			lines = append(lines, fmt.Sprint(args...))
		},
		Flush: func() {
			lock.Lock()
			defer lock.Unlock()
			if len(lines) == 0 {
				return
			}
			text := strings.Join(lines, "")
			lines = nil
			unix := time.Now().Unix()
			key := fmt.Sprintf("logs/%d.%s.%03d", unix, uid, count)
			count++
			err := lib.Retry(context.Background(), func() error {
				_, err := lib.S3Client().PutObjectWithContext(context.Background(), &s3.PutObjectInput{
					Bucket: aws.String(os.Getenv("PROJECT_BUCKET")),
					Key:    aws.String(key),
					Body:   bytes.NewReader([]byte(text)),
				})
				return err
			})
			if err != nil {
				lib.Logger.Println("error:", err)
				return
			}
		},
	}
	go func() {
		for {
			lib.Logger.Flush()
			select {
			case <-ctx.Done():
				return
			default:
				time.Sleep(5 * time.Second)
			}
		}
	}()
}

func main() {
	lambda.Start(handleRequest)
}

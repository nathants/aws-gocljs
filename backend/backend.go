package main

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	ddbtypes "github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	sdkLambda "github.com/aws/aws-sdk-go-v2/service/lambda"
	sdkLambdaTypes "github.com/aws/aws-sdk-go-v2/service/lambda/types"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/dustin/go-humanize"
	uuid "github.com/gofrs/uuid"
	"github.com/nathants/go-dynamolock"
	"github.com/nathants/libaws/lib"
)

type WebsocketKey struct {
	ID string `json:"id" dynamodbav:"id"`
}

type WebsocketData struct {
	ConnectionID string `json:"connection-id" dynamodbav:"connection-id"`
	Timestamp    string `json:"timestamp" dynamodbav:"timestamp"`
}

type Websocket struct {
	WebsocketKey
	WebsocketData
}

func index() events.APIGatewayProxyResponse {
	headers := map[string]string{
		"Content-Type":              "text/html; charset=UTF-8",
		"Content-Security-Policy":   "frame-ancestors 'none'; object-src 'none';",
		"X-Content-Type-Options":    "nosniff",
		"Strict-Transport-Security": "max-age=31536000; includeSubDomains;",
		"Referrer-Policy":           "strict-origin",
	}
	indexBytes, err := os.ReadFile("frontend/public/index.html.gz")
	if err == nil {
		headers["Content-Encoding"] = "gzip"
	} else {
		indexBytes, err = os.ReadFile("frontend/public/index.html")
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
	data, err := os.ReadFile("frontend/public" + path)
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

func httpVersionGet(_ context.Context, _ *events.APIGatewayProxyRequest, res chan<- events.APIGatewayProxyResponse) {
	val := map[string]string{}
	err := filepath.Walk(".", func(file string, _ os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		info, err := os.Stat(file)
		if err != nil {
			panic(err)
		}
		if info.IsDir() {
			return nil
		}
		data, err := os.ReadFile(file)
		if err != nil {
			panic(err)
		}
		hash := sha256.Sum256(data)
		hashHex := hex.EncodeToString(hash[:])
		size := humanize.Bytes(uint64(info.Size()))
		val[file] = fmt.Sprintf("%s %s", hashHex, size)
		return nil
	})
	if err != nil {
		panic(err)
	}
	data, err := json.Marshal(val)
	if err != nil {
		panic(err)
	}
	res <- events.APIGatewayProxyResponse{
		StatusCode: 200,
		Body:       string(data),
	}
}

func handleApiEvent(ctx context.Context, event *events.APIGatewayProxyRequest, res chan<- events.APIGatewayProxyResponse) {
	if event.Path == "/" {
		res <- index()
		return
	}
	if event.Path == "/_version" {
		httpVersionGet(ctx, event, res)
		return
	}
	if strings.HasPrefix(event.Path, "/js/main.js") ||
		strings.HasPrefix(event.Path, "/favicon.") {
		res <- static(event.Path)
		return
	}
	if strings.HasPrefix(event.Path, "/api/") {
		if event.HTTPMethod == http.MethodOptions {
			res <- events.APIGatewayProxyResponse{
				StatusCode: 200,
			}
			return
		}
		if event.Path == "/api/time" && event.HTTPMethod == http.MethodGet {
			httpTimeGet(ctx, event, res)
			return
		} else if event.Path == "/api/data" && event.HTTPMethod == http.MethodPost {
			httpDataPost(ctx, event, res)
			return
		}
		res <- notfound()
		return
	}
	res <- notfound()
}

type dataPostRequest struct {
	Data     string `json:"data"`
	MoreData string `json:"more-data"`
}

type dataPostResponse struct {
	Message string `json:"message"`
}

func httpDataPost(_ context.Context, req *events.APIGatewayProxyRequest, res chan<- events.APIGatewayProxyResponse) {
	input := dataPostRequest{}
	err := json.Unmarshal([]byte(req.Body), &input)
	if err != nil {
		panic(err)
	}
	output := dataPostResponse{
		Message: fmt.Sprintln("thanks for", input.Data, "and", input.MoreData),
	}
	data, err := json.Marshal(output)
	if err != nil {
	    panic(err)
	}
	time.Sleep(1*time.Second) // so we can see the linear-progress on the frontend
	res <- events.APIGatewayProxyResponse{
		StatusCode: 200,
		Body:       string(data),
		Headers: map[string]string{
			"Content-Type": "application/json",
		},
	}
}

type timeGetReponse struct {
	Time int `json:"time"`
}

func httpTimeGet(_ context.Context, _ *events.APIGatewayProxyRequest, res chan<- events.APIGatewayProxyResponse) {
	resp := timeGetReponse{
		Time: int(time.Now().UTC().Unix()),
	}
	data, err := json.Marshal(resp)
	if err != nil {
		panic(err)
	}
	res <- events.APIGatewayProxyResponse{
		StatusCode: 200,
		Body:       string(data),
		Headers:    map[string]string{"Content-Type": "application/json"},
	}
}

func logRecover(r any, res chan<- events.APIGatewayProxyResponse) {
	stack := string(debug.Stack())
	lib.Logger.Println(r)
	lib.Logger.Println(stack)
	res <- events.APIGatewayProxyResponse{
		StatusCode: 500,
		Body:       fmt.Sprint(r) + "\n" + stack,
	}
}

func handleWebsocketEvent(ctx context.Context, event *events.APIGatewayWebsocketProxyRequest, res chan<- events.APIGatewayProxyResponse) {
	record := Websocket{
		WebsocketKey: WebsocketKey{
			ID: fmt.Sprintf("websocket.%s", event.RequestContext.Identity.SourceIP),
		},
		WebsocketData: WebsocketData{
			Timestamp: time.Now().UTC().Format(time.RFC3339),
		},
	}
	switch event.RequestContext.RouteKey {
	case "$connect":
		item, err := attributevalue.MarshalMap(record.WebsocketKey)
		if err != nil {
			panic(err)
		}
		out, err := lib.DynamoDBClient().GetItem(ctx, &dynamodb.GetItemInput{
			Key:       item,
			TableName: aws.String(os.Getenv("PROJECT_NAME")),
		})
		if err != nil {
			panic(err)
		}
		if len(out.Item) != 0 {
			err = attributevalue.UnmarshalMap(out.Item, &record)
			if err != nil {
				panic(err)
			}
			connectionID := record.ConnectionID
			if connectionID != "" {
				_ = lib.ApiWebsocketClose(ctx, os.Getenv("PROJECT_DOMAIN_WEBSOCKET"), connectionID)
			}
		}
		record.ConnectionID = event.RequestContext.ConnectionID
		item, err = attributevalue.MarshalMap(record)
		if err != nil {
			panic(err)
		}
		_, err = lib.DynamoDBClient().PutItem(ctx, &dynamodb.PutItemInput{
			Item:      item,
			TableName: aws.String(os.Getenv("PROJECT_NAME")),
		})
		if err != nil {
			panic(err)
		}
		invokeWebsocketSender()
		res <- events.APIGatewayProxyResponse{StatusCode: 200}
		return
	case "$default":
		data, err := json.Marshal(map[string]string{
			"thanks for": event.Body,
		})
		if err != nil {
			panic(err)
		}
		err = lib.ApiWebsocketSend(ctx, os.Getenv("PROJECT_DOMAIN_WEBSOCKET"), event.RequestContext.ConnectionID, data)
		if err != nil {
			panic(err)
		}
		res <- events.APIGatewayProxyResponse{StatusCode: 200}
		return
	case "$disconnect":
		connectionID := event.RequestContext.ConnectionID
		_ = lib.ApiWebsocketClose(ctx, os.Getenv("PROJECT_DOMAIN_WEBSOCKET"), connectionID)
		item, err := attributevalue.MarshalMap(record.WebsocketKey)
		if err != nil {
			panic(err)
		}
		_, err = lib.DynamoDBClient().DeleteItem(ctx, &dynamodb.DeleteItemInput{
			Key:       item,
			TableName: aws.String(os.Getenv("PROJECT_NAME")),
		})
		if err != nil {
			panic(err)
		}
		res <- events.APIGatewayProxyResponse{StatusCode: 200}
		return
	default:
		panic(lib.PformatAlways(event))
	}
}

func websocketRouteKey(event map[string]any) string {
	val, ok := event["requestContext"].(map[string]any)
	if ok {
		routeKey, ok := val["routeKey"].(string)
		if ok {
			return routeKey
		}
	}
	return ""
}

func websocketConnectionID(event map[string]any) string {
	val, ok := event["requestContext"].(map[string]any)
	if ok {
		connectionID, ok := val["connectionId"].(string)
		if ok {
			return connectionID
		}
	}
	return ""
}

func invokeWebsocketSender() {
	data, err := json.Marshal(map[string]string{
		"detail-type": "websocket-sender",
	})
	if err != nil {
		panic(err)
	}
	invokeOut, err := lib.LambdaClient().Invoke(context.Background(), &sdkLambda.InvokeInput{
		FunctionName:   aws.String(os.Getenv("AWS_LAMBDA_FUNCTION_NAME")),
		InvocationType: sdkLambdaTypes.InvocationTypeEvent,
		LogType:        sdkLambdaTypes.LogTypeNone,
		Payload:        data,
	})
	if err != nil {
		panic(err)
	}
	if invokeOut.StatusCode != 202 {
		panic(fmt.Sprintf("status %d", invokeOut.StatusCode))
	}
}

type Data struct {
	Value string
}

func websocketSender(ctx context.Context, res chan<- events.APIGatewayProxyResponse) {
	defer func() {
		if r := recover(); r != nil {
			logRecover(r, res)
		}
	}()
	startTime := time.Now()
	table := os.Getenv("PROJECT_NAME")
	lockId := "lock.sender"
	maxAge := time.Second * 10
	heartbeat := time.Second * 5
	unlock, _, data, err := dynamolock.Lock[dynamolock.LockData](ctx, table, lockId, maxAge, heartbeat)
	if err != nil {
		// another sender is already running
		return
	}
	defer func() {
		err := unlock(data)
		if err != nil {
			panic(err)
		}
	}()
	for {
		// scan db for all open websockets
		count := 0
		var start map[string]ddbtypes.AttributeValue
		for {
			out, err := lib.DynamoDBClient().Scan(ctx, &dynamodb.ScanInput{
				TableName:         aws.String(os.Getenv("PROJECT_NAME")),
				ExclusiveStartKey: start,
			})
			if err != nil {
				lib.Logger.Println("error:", err)
				return
			}
			for _, item := range out.Items {
				val := Websocket{}
				err := attributevalue.UnmarshalMap(item, &val)
				if err != nil {
					lib.Logger.Println("error:", err)
					return
				}
				if strings.HasPrefix(val.ID, "websocket.") {
					t, err := time.Parse(time.RFC3339, val.Timestamp)
					if err != nil || time.Since(t) > 130*time.Minute {
						_ = lib.ApiWebsocketClose(ctx, os.Getenv("PROJECT_DOMAIN_WEBSOCKET"), val.ConnectionID)
						item, err := attributevalue.MarshalMap(val.WebsocketKey)
						if err != nil {
							lib.Logger.Println("error:", err)
							continue
						}
						_, err = lib.DynamoDBClient().DeleteItem(ctx, &dynamodb.DeleteItemInput{
							Key:       item,
							TableName: aws.String(os.Getenv("PROJECT_NAME")),
						})
						if err != nil {
							lib.Logger.Println("error:", err)
							continue
						}
					} else {
						// send the current time to each open websocket
						count++
						go func(val Websocket) {
							defer func() {
								if r := recover(); r != nil {
									logRecover(r, res)
								}
							}()
							data, err := json.Marshal(map[string]string{"time": timestamp()})
							if err != nil {
								lib.Logger.Println("error:", err)
								return
							}
							_ = lib.ApiWebsocketSend(ctx, os.Getenv("PROJECT_DOMAIN_WEBSOCKET"), val.ConnectionID, data)
						}(val)
					}
				}
			}
			if len(out.LastEvaluatedKey) == 0 {
				break
			}
			start = out.LastEvaluatedKey
		}
		// when no open connections, exit
		if count == 0 {
			return
		}
		time.Sleep(1 * time.Second)
		// start a new sender before this lambda times out
		if time.Since(startTime) > 14*time.Minute {
			invokeWebsocketSender()
			return
		}
	}
}

func handle(ctx context.Context, event map[string]any, res chan<- events.APIGatewayProxyResponse) {
	defer func() {
		if r := recover(); r != nil {
			logRecover(r, res)
		}
	}()
	if event["detail-type"] == "websocket-sender" {
		websocketSender(ctx, res)
		res <- events.APIGatewayProxyResponse{StatusCode: 200}
		return
	} else if websocketConnectionID(event) != "" {
		websocketEvent := &events.APIGatewayWebsocketProxyRequest{}
		data, err := json.Marshal(event)
		if err != nil {
			panic(err)
		}
		err = json.Unmarshal(data, &websocketEvent)
		if err != nil {
			panic(err)
		}
		handleWebsocketEvent(ctx, websocketEvent, res)
		return
	}
	_, ok := event["path"]
	if !ok {
		res <- notfound()
		return
	}
	apiEvent := &events.APIGatewayProxyRequest{}
	data, err := json.Marshal(event)
	if err != nil {
		panic(err)
	}
	err = json.Unmarshal(data, &apiEvent)
	if err != nil {
		panic(err)
	}
	handleApiEvent(ctx, apiEvent, res)
}

func sourceIP(event map[string]any) string {
	req, ok := event["requestContext"].(map[string]any)
	if ok {
		identity, ok := req["identity"].(map[string]any)
		if ok {
			sourceIP, ok := identity["sourceIp"].(string)
			if ok {
				return sourceIP
			}
		}
	}
	return ""
}

func timestamp() string {
	return time.Now().UTC().Format(time.RFC3339)
}

func handleRequest(ctx context.Context, event map[string]any) (events.APIGatewayProxyResponse, error) {
	setupLogging(ctx)
	defer lib.Logger.Flush()
	start := time.Now()
	res := make(chan events.APIGatewayProxyResponse)
	go handle(ctx, event, res)
	r := <-res
	routeKey := websocketRouteKey(event)
	path, pathOk := event["path"]
	if event["detail-type"] == "websocket-sender" {
		lib.Logger.Println("websocket-sender", time.Since(start), timestamp())
	} else if routeKey != "" {
		lib.Logger.Println("websocket", r.StatusCode, routeKey, time.Since(start), sourceIP(event), timestamp())
	} else if pathOk {
		method := event["httpMethod"]
		lib.Logger.Println("http", r.StatusCode, method, path, time.Since(start), sourceIP(event), timestamp())
	} else {
		lib.Logger.Println("async", time.Since(start), timestamp())
	}
	return r, nil
}

func setupLogging(ctx context.Context) {
	lock := sync.RWMutex{}
	var lines []string
	uid := uuid.Must(uuid.NewV4()).String()
	count := 0
	lib.Logger = &lib.LoggerStruct{
		Print: func(args ...any) {
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
				_, err := lib.S3Client().PutObject(context.Background(), &s3.PutObjectInput{
					Bucket: aws.String(os.Getenv("PROJECT_BUCKET")),
					Key:    aws.String(key),
					Body:   bytes.NewReader([]byte(text)),
				})
				return err
			})
			if err != nil {
				fmt.Println("error:", err)
			}
		},
	}
	go func() {
		// defer func() {}()
		for {
			select {
			case <-ctx.Done():
				return
			case <-time.After(5 * time.Second):
				lib.Logger.Flush()
			}
		}
	}()
}

func main() {
	lambda.Start(handleRequest)
}

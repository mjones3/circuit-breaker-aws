terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.61.0"
    }
  }
  required_version = ">= 1.2.0"
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

# IAM role & policy as before…
resource "aws_iam_role" "lambda_exec" {
  name = "circuitBreakerLambdaRole"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_logs" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "lambda_metrics" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchFullAccess"
}

# Create the Lambda function using that ZIP
resource "aws_lambda_function" "cb_function" {
  function_name = "circuitBreakerFunction"
  role          = aws_iam_role.lambda_exec.arn

  # point to your handler class
  handler = "com.mjones3.circuitbreaker.LambdaHandler::handleRequest"
  runtime = "java17"

  # zip with your flattened JAR
  filename         = "${path.module}/../circuit-breaker-function/target/circuit-breaker-function-0.0.1-SNAPSHOT.jar"
  source_code_hash = filebase64sha256("${path.module}/../circuit-breaker-function/target/circuit-breaker-function-0.0.1-SNAPSHOT.jar")


  # give the function up to 10 seconds
  timeout     = 10
  memory_size = 512

  environment {
    variables = {
      EXTERNAL_API_URL = "https://httpstat.us/503"
    }
  }
}

# HTTP API, integration, permissions, routes & stage… (unchanged)
resource "aws_apigatewayv2_api" "http_api" {
  name          = "circuitBreakerApi"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_integration" "lambda_integration" {
  api_id                 = aws_apigatewayv2_api.http_api.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.cb_function.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_lambda_permission" "allow_apigw" {
  statement_id  = "AllowAPIGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.cb_function.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.http_api.execution_arn}/*/*"
}

resource "aws_apigatewayv2_route" "default" {
  api_id    = aws_apigatewayv2_api.http_api.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_integration.id}"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.http_api.id
  name        = "$default"
  auto_deploy = true
}

output "api_endpoint" {
  description = "Invoke this URL with any path/method"
  value       = aws_apigatewayv2_api.http_api.api_endpoint
}

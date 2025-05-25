#!/usr/bin/env bash
set -euo pipefail

echo "=== Building Lambda JAR (with 'lambda' classifier) ==="
cd circuit-breaker-function/
mvn clean package -Plambda
cd ..

echo
echo "=== Initializing Terraform ==="
export TF_PLUGIN_TIMEOUT=120s
cd terraform
terraform init

echo
echo "=== Applying Terraform (auto-approve) ==="
terraform apply -auto-approve

echo
echo "=== Deployment Complete ==="
cd ..

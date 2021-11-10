#!/usr/bin/env bash

set -oeux

sudo podman-compose -f docker-compose-dev.yml up -d
sudo mkdir -p /run/postgresql
sudo chown admin:admin /run/postgresql
AWS_REGION=us-east-1 java -Xms1536m -Xmx1536m -server -cp datapub.jar clojure.main -m datapub.main

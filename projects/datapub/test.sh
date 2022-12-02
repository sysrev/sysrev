#!/usr/bin/env bash

set -eu

AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test clojure -X:test

#!/usr/bin/env bash

set -euo pipefail

./run-dev-containers.sh

clj -X:dev:repl

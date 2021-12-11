#!/usr/bin/env bash

set -eou pipefail

./run-dev-containers.sh

clojure -X:test

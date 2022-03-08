#!/usr/bin/env bash

set -eou pipefail

../../scripts/run-dev-containers

clojure -X:test

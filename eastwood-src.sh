#!/usr/bin/env bash

set -eu

clj -M:+postgres:dev:eastwood

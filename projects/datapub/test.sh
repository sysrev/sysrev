#!/usr/bin/env bash

if command -v podman-compose >/dev/null 2>&1; then
  podman-compose -f docker-compose-dev.yml up -d
else
  if command -v docker-compose >/dev/null 2>&1; then
    sudo docker-compose -f docker-compose-dev.yml up -d
  fi
fi

clojure -X:test

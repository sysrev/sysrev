#!/bin/bash
set -eux

for i in {1..10}; do
    echo "running iteration #$i"
    time lein test-aws-prod-browser
done

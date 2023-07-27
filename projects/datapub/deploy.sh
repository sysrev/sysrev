#!/usr/bin/env bash

set -e

./test.sh
./uberjar.sh
rsync target/datapub.jar ubuntu@sysrev.com:/data/datapub/datapub.jar --progress
ssh ubuntu@sysrev.com sudo systemctl restart datapub

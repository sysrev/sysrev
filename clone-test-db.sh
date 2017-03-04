#!/bin/bash
set -eu
SR_DEST_DB=sysrev_test SR_DEST_PORT=5432 ./scripts/clone-latest-db

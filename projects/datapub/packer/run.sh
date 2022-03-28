#!/usr/bin/env bash

set -eu

PATH=$PATH:/nix/var/nix/profiles/per-user/admin/profile/bin/

sudo docker-compose -f docker-compose-dev.yml start
sudo mkdir -p /run/postgresql
sudo chown admin:admin /run/postgresql

while ! test -f "/home/admin/datapub/datapub-config.local.edn"; do
  sleep 1
  echo "Waiting on /home/admin/datapub/datapub-config.local.edn to exist"
done

AWS_REGION=us-east-1 nix-shell --run "java -Xms1536m -Xmx1536m -server -cp datapub.jar clojure.main -m datapub.main"

#!/usr/bin/env bash

set -oeux

. /home/admin/.nix-profile/etc/profile.d/nix.sh

# We are collecting garbage from previous builds here.
# nix-collect-garbage removes our nixpkgs pin in default.nix, so we
# aren't calling it at the end to avoid redownloading it on server start.
# The ideal fix is to figure out how to make a gcroot for the nixpkgs pin.
nix-collect-garbage -d
rm -rf datapub
mkdir -p datapub/src
cd datapub
mv /tmp/datapub.jar /tmp/default.nix /tmp/docker-compose-dev.yml /tmp/run.sh .
nix-build -A inputDerivation -o gcroots
sudo docker-compose -f docker-compose-dev.yml up -d
cd
sudo chown root:root /tmp/datapub.service
sudo mv /tmp/datapub.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable datapub

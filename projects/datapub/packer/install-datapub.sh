#!/usr/bin/env bash

set -oeux

. /home/admin/.nix-profile/etc/profile.d/nix.sh

# We are collecting garbage from previous builds here.
# nix-collect-garbage removes our nixpkgs pin in default.nix, so we
# aren't calling it at the end to avoid redownloading it on server start.
# The ideal fix is to figure out how to make a gcroot for the nixpkgs pin.
# bash-interactive for nix-shell is also not a gcroot.
nix-collect-garbage -d
rm -rf datapub
mkdir -p datapub/src
cd datapub
mv /tmp/datapub.jar /tmp/default.nix /tmp/run.sh .
nix-build -A inputDerivation -o gcroots
nix-shell --run "true"
cd
sudo chown root:root /tmp/datapub.service
sudo mv /tmp/datapub.service /etc/systemd/system/
sudo mkdir -p /var/log/datapub
sudo systemctl daemon-reload
sudo systemctl enable datapub

#!/bin/bash

set -eu

lessc -v > /dev/null || (echo && echo "Error: lessc needed ('sudo npm install -g less')" && false)

echo "Installing semantic through NPM"
npm install > /dev/null
echo

echo "Building semantic CSS"
./scripts/build-semantic default > /dev/null
./scripts/build-semantic dark > /dev/null
echo

echo "Building site CSS"
./scripts/build-css > /dev/null
echo

echo "Done"
echo
echo "Remember to link ./sysrev.dev.nginx.site into /etc/nginx/sites-enabled"
echo

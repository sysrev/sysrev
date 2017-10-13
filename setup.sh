#!/bin/bash

set -eu

npm -v > /dev/null || (echo && echo "Error: npm not found (install Node/NPM on system)" && false)
lessc -v > /dev/null || (echo && echo "Error: lessc not found ('sudo npm install -g less')" && false)
gulp -v > /dev/null || (echo && echo "Error: gulp not found ('sudo npm install -g gulp')" && false)

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

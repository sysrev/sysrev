#!/bin/bash

set -eu

echo "Making semantic directory"
mkdir -p resources/public/semantic

echo "Installing semantic through NPM"
npm install > /dev/null

echo "Building semantic into resources/public/semantic"
cd ./semantic && gulp build > /dev/null

echo "Installing lein deps"
lein deps > /dev/null

echo "Done"
echo
echo "Remember to link ./sysrev.dev.nginx.site into /etc/nginx/sites-enabled"
echo

true

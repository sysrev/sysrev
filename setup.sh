#!/bin/bash

set -eu

lessc -v > /dev/null || (echo && echo "Error: lessc needed ('sudo npm install -g less')" && false)

echo "Making semantic directory"
mkdir -p resources/public/semantic
echo

echo "Installing semantic through NPM"
npm install > /dev/null
echo

echo "Building semantic into resources/public/semantic"
cd ./semantic && gulp build > /dev/null && cd ..
echo

echo "Building style.css from src/less"
./scripts/build-css
echo

echo "Done"
echo
echo "Remember to link ./sysrev.dev.nginx.site into /etc/nginx/sites-enabled"
echo

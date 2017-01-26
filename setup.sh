#!/bin/bash

echo "Making semantic directory"
mkdir -p resources/public/semantic

npm install

echo "Building semantic into resources/public/semantic"
cd ./semantic && gulp build

lein deps
lein bower install
lein cljsbuild once dev

echo "Remember to link ./sysrev.dev.nginx.site into /etc/nginx/sites-enabled"
echo "Done"

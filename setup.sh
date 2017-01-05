#!/bin/bash

npm install

lein deps
lein bower install
lein cljsbuild once dev

echo "Building semantic"
cd ./semantic && gulp build

echo "Linking semantic build directory"
ln -sf ./semantic/dist ./resources/public/semantic

echo "Remember to link ./sysrev.dev.nginx.site into /etc/nginx/sites-enabled"
echo "Done"

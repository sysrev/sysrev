#!/bin/bash

set -eu

npm -v > /dev/null || (echo && echo "Error: npm not found (install Node/NPM on system)" && false)
lessc -v > /dev/null || (echo && echo "Error: lessc not found ('sudo npm install -g less')" && false)
gulp -v > /dev/null || (echo && echo "Error: gulp not found ('sudo npm install -g gulp')" && false)

echo "Installing semantic through NPM"
npm install > /dev/null
echo

echo "Building CSS"
./scripts/build-all-css
echo

echo
echo "You will need to create file vars.sh (see vars.sh.template) and manually add the required private values"

echo "Done"
echo

#!/bin/bash

set -eu

function check_deps () {
  npm -v > /dev/null || (echo && echo "Error: npm not found (install Node/NPM on system)" && false)
  lessc -v > /dev/null || (echo && echo "Error: lessc not found ('sudo npm install -g less')" && false)
  gulp -v > /dev/null || (echo && echo "Error: gulp not found ('sudo npm install -g gulp')" && false)
  md5sum --version > /dev/null || (echo && echo "Error: md5sum not found (install system package)")
}

function install_semantic () {
  echo -n "Installing semantic through NPM ... "
  ./scripts/install-semantic reinstall > /dev/null 2> /dev/null
  echo "done"
}

function copy_client_semantic () {
  echo -n "Copying semantic directory to client/ ... "
  rm -rf client/semantic || true
  cp -r semantic client/
  echo "done"
}

function install_client () {
  echo -n "Installing client packages from NPM ... "
  cd client
  npm install > /dev/null 2> /dev/null
  cd ..
  echo "done"
}

function install_flyway () {
  echo -n "Installing flyway ... "
  ./scripts/install-flyway > /dev/null
  echo "done"
}

check_deps
time install_semantic ; echo
copy_client_semantic ; echo
time install_client ; echo
install_flyway ; echo
echo "You will need to create file vars.sh (see vars.sh.template) and manually add the required private values."

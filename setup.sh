#!/usr/bin/env bash

set -eu

export PATH=$PWD/scripts/:$PATH

function check_deps () {
  set -eu
  echo -n "Checking for required programs (npm md5sum) ... "
  npm -v > /dev/null ||
    (echo -e "\nError: npm not found (install Node/NPM on system)" && false)
  md5sum --version > /dev/null ||
    (echo -e "\nError: md5sum not found (install system package)" && false)
  echo "done"
}

function install_semantic () {
  set -eu
  echo -n "Installing semantic through NPM ... "
  install-semantic reinstall > /dev/null 2>&1 || (echo "failed" && false)
  echo "done"
}

function copy_client_semantic () {
  set -eu
  echo -n "Copying semantic directory to client/ ... "
  rm -rf client/semantic || true
  cp -r semantic client/
  echo "done"
}

function install_client () {
  set -eu
  echo -n "Installing client packages from NPM ... "
  cd client
  npm install > /dev/null 2>&1 || (echo "failed" && false)
  cd ..
  echo "done"
}

function install_flyway () {
  set -eu
  echo -n "Installing flyway ... "
  install-flyway > /dev/null || (echo "failed" && false)
  echo "done"
}

echo ; check_deps
echo ; time install_semantic
echo ; copy_client_semantic
echo ; time install_client
echo ; install_flyway
echo

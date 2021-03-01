#!/bin/bash

set -eu

export PATH=$PWD/scripts/:$PATH

function check_deps () {
  set -eu
  echo -n "Checking for required programs (npm lessc gulp md5sum) ... "
  npm -v > /dev/null ||
    (echo -e "\nError: npm not found (install Node/NPM on system)" && false)
  lessc -v > /dev/null ||
    (echo -e "\nError: lessc not found ('sudo npm install -g less')" && false)
  gulp -v > /dev/null ||
    (echo -e "\nError: gulp not found ('sudo npm install -g gulp')" && false)
  md5sum --version > /dev/null ||
    (echo -e "\nError: md5sum not found (install system package)" && false)
  echo "done"
}

function install_semantic () {
  set -eu
  echo -n "Installing semantic through NPM ... "
  install-semantic reinstall > /dev/null 2> /dev/null
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
  npm install > /dev/null 2> /dev/null
  cd ..
  echo "done"
}

function install_flyway () {
  set -eu
  echo -n "Installing flyway ... "
  install-flyway > /dev/null
  echo "done"
}

function install_clj_kondo() {
  set -eu
  if [ -e "$PWD/scripts/clj-kondo" ] ; then
    echo "clj-kondo already installed to ./scripts/"
  else
    echo -n "Installing clj-kondo [ ./scripts/clj-kondo ] ... "
    install-clj-kondo > /dev/null
    echo "done"
  fi
}

check_deps ; echo
time install_semantic ; echo
copy_client_semantic ; echo
time install_client ; echo
install_flyway ; echo
install_clj_kondo ; echo
echo "You will need to create file vars.sh (see vars.sh.template) and manually add the required private values."

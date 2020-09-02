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

function check_clj_kondo () {
  set -eu
  clj-kondo --version > /dev/null ||
    (echo -en "\nError: clj-kondo binary not found "
     echo "(https://github.com/borkdude/clj-kondo/blob/master/doc/install.md)"
     false)
}

function init_clj_kondo__run () {
  set -eu
  echo "Initializing clj-kondo project cache ..."
  check_clj_kondo
  prefix-lines init-clj-kondo
  echo "done"
}

function init_clj_kondo () {
  if [ -e "/var/lib/jenkins/jobs/sysrev" ] ; then
    echo "Skipping init-clj-kondo; Jenkins build environment identified" ; echo
  else
    if [ ! -d '.clj-kondo/.cache' ] ; then
      time init_clj_kondo__run ; echo
    else
      echo "Skipping init-clj-kondo (found existing .clj-kondo/.cache)" ; echo
    fi
  fi
}

check_deps ; echo
time install_semantic ; echo
copy_client_semantic ; echo
time install_client ; echo
install_flyway ; echo
init_clj_kondo
echo "You will need to create file vars.sh (see vars.sh.template) and manually add the required private values."

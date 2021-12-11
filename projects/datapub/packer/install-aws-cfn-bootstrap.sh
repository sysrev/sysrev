#!/usr/bin/env bash

set -oeux

wget https://s3.amazonaws.com/cloudformation-examples/aws-cfn-bootstrap-py3-latest.tar.gz
tar xzf aws-cfn-bootstrap-py3-latest.tar.gz
cd aws-cfn-bootstrap-*/
sudo python3 setup.py install
cd ..
sudo rm -rf aws-cfn-bootstrap*

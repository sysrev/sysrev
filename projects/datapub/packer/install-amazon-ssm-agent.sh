#!/usr/bin/env bash

set -oeux

wget https://s3.us-east-1.amazonaws.com/amazon-ssm-us-east-1/latest/debian_amd64/amazon-ssm-agent.deb
sudo dpkg -i amazon-ssm-agent.deb
rm amazon-ssm-agent.deb

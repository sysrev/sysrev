#!/usr/bin/env bash

set -oeux

sudo amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json
sudo amazon-cloudwatch-agent-ctl -a start

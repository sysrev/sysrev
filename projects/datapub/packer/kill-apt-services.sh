#!/usr/bin/env bash

# https://codeinthehole.com/tips/avoiding-package-lockout-in-ubuntu-1804/

set -e

function killService() {
    service=$1
    sudo systemctl stop $service
    sudo systemctl kill --kill-who=all $service

    # Wait until the status of the service is exited, killed, or inactive.
    while ! (sudo systemctl status "$service" | grep -q "\(Main.*code=\(exited\|killed\)\)\|\(Active: inactive .dead.\)")
    do
        echo "Waiting on $service to exit..."
        sleep 3
    done
}

function disableTimers() {
    sudo systemctl disable apt-daily.timer
    sudo systemctl disable apt-daily-upgrade.timer
}

function killServices() {
    killService unattended-upgrades.service
    killService apt-daily.service
    killService apt-daily-upgrade.service
}

function main() {
    disableTimers
    killServices
}

main

#!/usr/bin/fish

set -l user (whoami)
set -l sbtScreen {$user}_sbt
set -l leinScreen {$user}_lein

screen -S $sbtScreen -X quit
screen -S $leinScreen -X quit

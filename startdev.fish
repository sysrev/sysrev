#!/usr/bin/fish

set -l user (whoami)
set -l sbtScreen {$user}_sbt
set -l leinScreen {$user}_lein

screen -dmS $sbtScreen
screen -S $sbtScreen -X stuff "sbt \"~;jetty:stop;jetty:start\"
" 

cd ./sysrev-web
screen -dmS $leinScreen
screen -S $leinScreen -X stuff "rlwrap lein figwheel dev
"

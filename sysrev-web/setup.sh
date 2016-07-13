#!/bin/bash

npm install semantic-ui@2.2.2

lein deps
lein bower install
lein cljsbuild once dev

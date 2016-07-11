#!/bin/bash

npm install semantic-ui@2.1.8

lein deps
lein cljsbuild once dev

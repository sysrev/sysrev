#!/bin/bash

# https://gist.github.com/joelittlejohn/4729776
# Very quick and dirty command to find unused functions and vars in a Clojure project

for f in $(egrep -o -R "defn?-? [^ ]*" * --include '*.cljs' | cut -d \  -f 2 | sort | uniq); do
    echo $f $(grep -R --include '*.cljs' -- "$f" * | wc -l);
done | grep " 1$"

#!/usr/bin/env bash

set -eu

# https://gist.github.com/joelittlejohn/4729776
# Very quick and dirty command to find unused functions and vars in a Clojure project

for f in $(egrep -o -R "defn?-?(once)?(spec)?( \^\:[^ ]+)* [^ ]*" * --include '*.clj' \
                 --exclude-dir "custom" \
                 --exclude 'misc.clj' |
               grep -v \/def |
               grep -v \^\:repl |
               grep -v \^\:unused |
               cut -d \  -f 2 | sort | uniq); do
    echo $f $(grep -R --include '*.clj' -- "$f" * |
                  grep -v \/fdef |
                  wc -l);
done | grep " 1$"

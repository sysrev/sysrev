(ns sysrev.pdf-read.interface-test
  (:require [clojure.test :refer [are deftest is]]
            [sysrev.pdf-read.interface :as pdf-read]))

(deftest ^:unit test-condense-text
  (are [a b] (= a (pdf-read/condense-text b))
    "a" "a   \n"
    "a b" "a  b"
    "" "\u0000"
    "a b" "a\u0000  b"
    "thorough-going" "thorough-going"
    "thoroughgoing" "thorough-\ngoing"
    "thoroughgoing" "thorough-\n going"))

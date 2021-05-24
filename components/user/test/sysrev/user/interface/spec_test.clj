(ns sysrev.user.interface.spec-test
  (:require [clojure.spec.alpha :as s]
            [sysrev.user.interface.spec :as su])
  (:use clojure.test))

(deftest test-spec
  (are [a] (s/valid? ::su/username a)
    "a"
    "λ"
    "λθ"
    "λ-a"
    "tester")
  (are [a] (not (s/valid? ::su/username a))
    ""
    " "
    "\t"
    "\n"
    "-"
    "--"
    "λ-"
    "λ--θ"
    "-a"
    "a b"
    "a.b"
    "@"
    "tester@insilica.co"))

(ns sysrev.user.interface.spec-test
  (:require #_[clojure.spec.alpha :as s]
            #_[sysrev.user.interface.spec :as su])
  (:use clojure.test))

#_
(deftest test-spec
  (are [a] (s/valid? ;::su/username a
            )
    "a"
    "aB"
    "a-b"
    "A-b"
    "tester")
  (are [a] (not (s/valid? ;::su/username a
                 ))
    "λ"
    "λθ"
    "λ-a"
    ""
    " "
    "\t"
    "\n"
    "-"
    "--"
    "a-"
    "a--b"
    "λ-"
    "λ--θ"
    "-a"
    "a b"
    "a.b"
    "a_b"
    "@"
    "tester@insilica.co"))

(ns sysrev.user.interface.spec-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :refer :all]
   [sysrev.user.interface.spec :as su]))

(deftest ^:unit test-spec
  (are [a] (s/valid? ::su/username a)
    "a"
    "aB"
    "a-b"
    "A-b"
    "tester")
  (are [a] (not (s/valid? ::su/username a))
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

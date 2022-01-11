(ns sysrev.junit.interface.spec
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::attrs map?)
(s/def ::content-item (s/or :element ::xml-element
                            :string string?))
(s/def ::content (s/every ::content-item))
(s/def ::tag keyword?)
(s/def ::xml-element (s/keys :req-un [::attrs ::content ::tag]))

(s/def ::whitespace (s/and string? #(re-matches #"\s*" %)))

(s/def ::testsuite
  (s/and ::xml-element #(= :testsuite (:tag %))))

(s/def :sysrev.junit.interface.spec.testsuites/content
  (s/every (s/or :testsuite ::testsuite
                 :whitespace ::whitespace)))
(s/def ::testsuites
  (s/and ::xml-element #(= :testsuites (:tag %))
         (s/keys :req-un [:sysrev.junit.interface.spec.testsuites/content])))

(ns sysrev.junit.interface
  (:require
   [sysrev.junit.core :as core]))

(defn merge-xml
  "Merge any number of JUnit XML documents."
  [& ms]
  (apply core/merge-xml ms))

(ns sysrev.junit.interface
  (:require
   [sysrev.junit.core :as core]))

(defn merge-files!
  "Merge any number of in-files into out-file. Files may be specified as
  `java.nio.file.Path` objects or as strings."
  [out-file in-files]
  (core/merge-files! out-file in-files))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn merge-xml
  "Merge any number of JUnit XML documents."
  [& ms]
  (apply core/merge-xml ms))

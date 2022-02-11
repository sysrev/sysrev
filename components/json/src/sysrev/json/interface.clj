(ns sysrev.json.interface
  "Low-dependency JSON parsing and generation.

  See https://corfield.org/blog/2021/03/25/little-things/ for rationale"
  (:require
   [sysrev.json.core :as core])
  (:import
   (java.io Writer)))

(defn write
  "Write JSON-formatted output to a `java.io.Writer`. `opts` are
   the same as the options to `clojure.data.json/write`."
  [x ^Writer writer & {:as opts}]
  (core/write x writer opts))

(defn write-str
  "Converts `x` to a JSON-formatted string. `opts` are
   the same as the options to `clojure.data.json/write`."
  ^String [x & {:as opts}]
  (core/write-str x opts))


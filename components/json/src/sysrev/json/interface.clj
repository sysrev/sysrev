(ns sysrev.json.interface
  "Low-dependency JSON parsing and generation.

  See https://corfield.org/blog/2021/03/25/little-things/ for rationale"
  (:refer-clojure :exclude [read])
  (:require
   [sysrev.json.core :as core])
  (:import
   (java.io Reader Writer)))

(defn read
  "Returns a single item of JSON data from a java.io.Reader. `opts` are
  the same as the options to `insilica.data.json/read`.

  Passes `:throw-on-extra-input? true` by default."
  [^Reader reader & {:as opts}]
  (core/read reader opts))

(defn read-str
  "Returns a single item of JSON data from a String. `opts` are
  the same as the options to `insilica.data.json/read`.

  Passes `:throw-on-extra-input? true` by default."
  [^String s & {:as opts}]
  (core/read-str s opts))

(defn write
  "Write JSON-formatted output to a `java.io.Writer`. `opts` are
   the same as the options to `insilica.data.json/write`."
  [x ^Writer writer & {:as opts}]
  (core/write x writer opts))

(defn write-str
  "Converts `x` to a JSON-formatted string. `opts` are
   the same as the options to `insilica.data.json/write`."
  ^String [x & {:as opts}]
  (core/write-str x opts))


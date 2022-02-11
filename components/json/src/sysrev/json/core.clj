(ns sysrev.json.core
  (:refer-clojure :exclude [read])
  (:require
   [insilica.data.json :as json])
  (:import
   (java.io Reader StringWriter Writer)))

(def default-read-options
  {:bigdec false
   :key-fn nil
   :throw-on-extra-input? true
   :value-fn nil})

(defn read [^Reader reader & {:as opts}]
  (json/read reader (merge default-read-options opts)))

(defn read-str [^String s & {:as opts}]
  (json/read-str s (merge default-read-options opts)))

(defn write [x ^Writer writer & {:as opts}]
  (json/write x writer opts))

(defn write-str ^String [x & {:as opts}]
  (let [sw (StringWriter.)]
    (write x sw opts)
    (.toString sw)))

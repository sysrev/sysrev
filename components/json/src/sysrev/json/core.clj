(ns sysrev.json.core
  (:require
   [clojure.data.json :as json])
  (:import
   (java.io StringWriter Writer)))

(defn write [x ^Writer writer & {:as opts}]
  (json/write x writer opts))

(defn write-str ^String [x & {:as opts}]
  (let [sw (StringWriter.)]
    (write x sw opts)
    (.toString sw)))

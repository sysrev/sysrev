(ns sysrev.config.core
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   (java.io PushbackReader)))

(defn get-config [filename]
  (if-let [resource (io/resource filename)]
    (with-open [reader (-> resource io/reader PushbackReader.)]
      (try
        (edn/read reader)
        (catch Exception e
          (throw
           (ex-info (str "Error parsing EDN in config file \"" filename
                         \"": " (.getMessage e))
                    {:filename filename}
                    e)))))
    (throw
     (ex-info (str "Config file not found: \"" filename "\"")
              {:filename filename}))))

(ns sysrev.config.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn get-config [filename]
  (if-let [r (io/resource filename)]
    (let [s (slurp r)]
      (try
        (edn/read-string s)
        (catch Exception e
          (throw
           (ex-info (str "Error parsing EDN in config file \"" filename
                         \"": " (.getMessage e))
                    {:filename filename}
                    e)))))
    (throw
     (ex-info (str "Config file not found: \"" filename "\"")
              {:filename filename}))))

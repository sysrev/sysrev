(ns sysrev.config
  "Wrapper interface of https://github.com/weavejester/environ"
  (:require [environ.core :as environ]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import java.io.PushbackReader))

(defn read-config-file [f]
  (when-let [url (io/resource f)]
    (with-open [r (-> url io/reader PushbackReader.)]
      (edn/read r))))

(defonce ^{:doc "A map of environment variables."
           :dynamic true}
  env (let [{:keys [private-config] :as config} (read-config-file "config.edn")]
        (merge config
               environ/env
               (some-> private-config read-config-file))))

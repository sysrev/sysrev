(ns sysrev.config
  "Wrapper interface of https://github.com/weavejester/environ"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [environ.core :as environ]
   [medley.core :as medley])
  (:import
   (java.io PushbackReader)))

(defn read-config-file [f]
  (when-let [url (io/resource f)]
    (with-open [r (-> url io/reader PushbackReader.)]
      (edn/read r))))

(defonce ^{:doc "A map of environment variables."
           :dynamic true}
  env (let [{:keys [private-config] :as config} (read-config-file "config.edn")]
        (medley/deep-merge
         config
         ;; Remove some large values that we don't need to aid debugging.
         (dissoc environ/env :java-class-path :ls-colors)
         (some-> private-config read-config-file))))

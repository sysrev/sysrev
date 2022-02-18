(ns sysrev.config
  "Wrapper interface of https://github.com/weavejester/environ"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [environ.core :as environ])
  (:import
   (java.io PushbackReader)))

(def defaults
  {:postgres {:dbtype "postgres"
              :flyway-locations ["classpath:/sql"]}})

(defn deep-merge [& args]
  (if (every? #(or (map? %) (nil? %)) args)
    (apply merge-with deep-merge args)
    (last args)))

(defn read-config-file [f]
  (when-let [url (io/resource f)]
    (with-open [r (-> url io/reader PushbackReader.)]
      (edn/read r))))

(defonce ^{:doc "A map of environment variables."
           :dynamic true}
  env (let [{:keys [private-config] :as config} (read-config-file "config.edn")
            local-file-config (io/file "config.local.edn")]
        (deep-merge
         defaults
         config
         ;; Remove some large values that we don't need to aid debugging.
         (dissoc environ/env :java-class-path :ls-colors)
         (some-> private-config read-config-file)
         (when (some-> local-file-config .exists)
           (-> local-file-config io/reader PushbackReader. edn/read)))))

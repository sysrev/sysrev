(ns datapub.auth
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]))

(defn bearer-token [context]
  (let [[kind token] (some-> context :request :headers (get "authorization")
                             (str/split #" " 2))]
    (when (and (seq token) (seq kind) (= "bearer" (str/lower-case kind)))
      token)))

(defn sysrev-dev-key [context]
  (let [k (-> context :pedestal :config :secrets :sysrev-dev-key)]
    (if (seq k)
      k
      (throw (RuntimeException. "sysrev-dev-key not found")))))

(defn sysrev-dev? [context]
  (= (sysrev-dev-key context) (bearer-token context)))

(defn unsign [context data]
  (jwt/unsign data (sysrev-dev-key context)))

(defn can-read-dataset? [context dataset-id]
  (or (sysrev-dev? context)
      (let [jwt (some->> (bearer-token context) (unsign context))]
        (boolean
         (and (= (str dataset-id) (:dataset-id jwt))
              (some #{"read"} (:permissions jwt)))))))

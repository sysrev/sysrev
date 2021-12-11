(ns sysrev.article.graphql
  (:require [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [sysrev.label.answer :as answer]
            [sysrev.db.core :as db]
            [cheshire.core :as cheshire]
            [clojure.walk :refer [keywordize-keys]]
            [sysrev.user.interface :refer [user-by-api-token]]
            [sysrev.util :as util :refer [uuid-from-string]]))

(defn parse-input-values [[label-id value]]
  [(uuid-from-string label-id)
   (cond
     (and (map? value) (get value "labels"))
     {:labels
      (->> (get value "labels")
           (map-indexed (fn [idx v]
                          [(str idx)
                           (->> v (map parse-input-values) (into {}))]))
           (into {}))}

     (map? value)
     (keywordize-keys value)

     (or (boolean? value)
         (vector? value)
         (string? value))
     value

     :else
     (throw (new Exception (str "Value needs to be a String, Boolean, Map or Vector: " value))))])

(defn parse-set-label-input [v]
  (->> v
       cheshire/parse-string
       (map parse-input-values)
       (into {})))

(defn serialize-set-label-input [v]
  (cheshire/generate-string v))

(def set-labels!
  ^ResolverResult
  (fn [context {article-id :articleID
                project-id :projectID
                label-values :labelValues
                confirm? :confirm
                resolve? :resolve
                change? :change} _]
    (db/with-clear-project-cache project-id
      (let [api-token (:authorization context)
            user (user-by-api-token api-token)
            user-id (:user-id user)]
        (answer/set-labels {:project-id project-id
                            :user-id user-id
                            :article-id article-id
                            :label-values label-values
                            :confirm? confirm?
                            :change? change?
                            :resolve? resolve?})
        (resolve-as true)))))

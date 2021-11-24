(ns sysrev.article.graphql
  (:require [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [sysrev.label.answer :as answer]
            [sysrev.db.core :as db]
            [sysrev.user.interface :refer [user-by-api-token]]
            [sysrev.util :as util :refer [uuid-from-string]]))

(defn parse-input-values [v]
  (let [label-id (:labelId v)
        value (:value v)]
    [(uuid-from-string label-id) (cond
                                   (seq? value)
                                   {:labels
                                    (->> value
                                         (map-indexed (fn [idx v]
                                                        (if (seq? v)
                                                          [(str idx)
                                                           (->> v (map parse-input-values) (into {}))] 
                                                          (throw (new Exception (str "Value is not a vector: " v))))))
                                         (into {}))}

                                   (map? value)
                                   (parse-input-values value)

                                   (or (boolean? value)
                                       (string? value))
                                   value

                                   :else
                                   (throw (new Exception (str "Value needs to be a String, Boolean, Map or Vector: " value))))]))

(defn parse-set-label-input [v]
  (if (map? v)
    (parse-input-values v)
    (throw (new Exception "SetLabelInput values need to be a map: " v))))


(defn ^ResolverResult set-labels!
  [context {article-id :articleID
            project-id :projectID
            label-values :labelValues
            confirm? :confirm
            resolve? :resolve
            change? :change} _]
  (db/with-clear-project-cache project-id
    (let [api-token (:authorization context)
          user (user-by-api-token api-token)
          user-id (:user-id user)
          label-values-map (into {} label-values)]
      (answer/set-labels {:project-id project-id
                          :user-id user-id
                          :article-id article-id
                          :label-values label-values-map
                          :confirm? confirm?
                          :change? change?
                          :resolve? resolve?})
      (resolve-as true))))

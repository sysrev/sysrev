(ns sysrev.web.ajax
  (:require [clojure.data.json :as json]
            [ring.util.response :as r]
            [sysrev.db.articles :as articles]
            [sysrev.db.users :as users]
            [sysrev.db.core :refer [mapify-by-id]]
            [sysrev.util :refer [parse-number]]))

(defn wrap-json
  "Create an HTTP response with content of OBJ converted to a JSON string."
  [obj]
  (-> obj
      (json/write-str)
      (r/response)
      (r/header "Content-Type" "application/json; charset=utf-8")))

(defn integerify-map-keys
  "Maps parsed from JSON with integer keys will have the integers changed 
  to keywords. This converts any integer keywords back to integers, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-int (-> k name parse-number)
                       k-new (if (integer? k-int) k-int k)
                       ;; integerify sub-maps recursively
                       v-new (if (map? v)
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn get-user-id [request]
  (let [email (-> request :session :identity)]
    (and email (:id (users/get-user-by-email email)))))

(defn web-criteria []
  (let [cs (articles/all-criteria)]
    (->> cs
         (map #(vector (:criteria_id %) (dissoc % :criteria_id)))
         (apply concat)
         (apply hash-map))))

(defn web-all-labels []
  {:labels
   (articles/all-article-labels :criteria_id :user_id :answer)
   :articles
   (articles/all-labeled-articles)})

(defn web-project-users []
  (users/get-user-summaries))

(defn web-label-task [user-id n-max & [above-score]]
  (if user-id
    {:result (users/get-user-label-tasks
              user-id n-max above-score)}
    {:error true}))

(defn web-article-labels [article-id]
  (let [entries (articles/all-labels-for-article article-id)
        user-ids (->> entries (map :user_id) distinct)]
    (->> user-ids
         (map (fn [user-id]
                [user-id
                 (->> entries
                      (filter #(= (:user_id %) user-id))
                      (map #(do [(:criteria_id %)
                                 (:answer %)]))
                      (apply concat)
                      (apply hash-map))]))
         (apply concat)
         (apply hash-map))))

(defn web-set-labels [request]
  (if-let [user-id (get-user-id request)]
    (try
      (let [fields (-> request :body slurp
                       (json/read-str :key-fn keyword)
                       integerify-map-keys)
            article-id (:article-id fields)
            label-values (:label-values fields)]
        (users/set-user-article-labels user-id article-id label-values)
        {:result fields})
      (catch Exception e
        (println e)
        {:error "database error"}))
    {:error "not logged in"}))

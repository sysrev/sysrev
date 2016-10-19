(ns sysrev.web.ajax
  (:require [clojure.data.json :as json]
            [ring.util.response :as r]
            [sysrev.db.articles :as articles]
            [sysrev.db.users :as users]
            [sysrev.db.sysrev :as sysrev]
            [sysrev.util :refer [parse-number integerify-map-keys]]))

(defn wrap-json
  "Create an HTTP response with content of OBJ converted to a JSON string."
  [obj]
  (-> obj
      (json/write-str)
      (r/response)
      (r/header "Content-Type" "application/json; charset=utf-8")
      (r/header "Cache-Control" "no-cache, no-store")))

(defn get-user-id [request]
  (let [email (-> request :session :identity)]
    (and email (:id (users/get-user-by-email email)))))

(defn web-criteria []
  (let [cs (articles/all-criteria)]
    (->> cs
         (map #(vector (:criteria_id %) (dissoc % :criteria_id)))
         (apply concat)
         (apply hash-map))))

(defn web-project-summary []
  (let [[users stats]
        (pvalues (users/get-user-summaries) (sysrev/sr-summary))]
    {:users users :stats stats}))

(defn web-user-info [user-id self?]
  (let [umap (users/get-user-info user-id)]
    (if true ;;self?
      umap
      (assoc-in umap [:labels :unconfirmed] nil))))

(defn web-label-task [user-id n-max & [above-score]]
  (if user-id
    {:result (users/get-user-label-tasks
              user-id n-max above-score)}
    {:error true}))

(defn web-article-info [article-id]
  (let [[article raw-labels]
        (pvalues (articles/get-article article-id)
                 (articles/all-labels-for-article article-id))
        user-ids (->> raw-labels (map :user_id) distinct)
        labels
        (->> user-ids
             (map (fn [user-id]
                    [user-id
                     (->> raw-labels
                          (filter #(= (:user_id %) user-id))
                          (map #(do [(:criteria_id %)
                                     (:answer %)]))
                          (apply concat)
                          (apply hash-map))]))
             (apply concat)
             (apply hash-map))]
    {:article article
     :labels labels}))

(defn web-set-labels [request confirm?]
  (if-let [user-id (get-user-id request)]
    (try
      (let [fields (-> request :body slurp
                       (json/read-str :key-fn keyword)
                       integerify-map-keys)
            article-id (:article-id fields)
            label-values (:label-values fields)]
        (assert (not (users/user-article-confirmed? user-id article-id)))
        (users/set-user-article-labels user-id article-id label-values false)
        (when confirm?
          (users/confirm-user-article-labels user-id article-id))
        {:result fields})
      (catch Exception e
        (println e)
        {:error "database error"}))
    {:error "not logged in"}))

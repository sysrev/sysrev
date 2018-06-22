(ns sysrev.db.article_list
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [sysrev.db.core :as db :refer
             [do-query do-execute to-sql-array sql-now with-project-cache
              clear-project-cache to-jsonb]]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :as u]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.article :as sa]))

(defn project-full-article-list [project-id]
  (with-project-cache
    project-id [:full-article-list]
    (let [articles
          (-> (q/select-project-articles
               project-id [:a.article-id :a.abstract :a.primary-title
                           :a.secondary-title :a.date :a.year :a.authors
                           :a.keywords])
              (->> do-query
                   (group-by :article-id)
                   (u/map-values first)))
          alabels
          (-> (q/select-project-articles
               project-id [:al.article-id :al.label-id :al.user-id
                           :al.answer :al.inclusion :al.updated-time
                           :al.confirm-time :al.resolve])
              (q/join-article-labels)
              (q/filter-valid-article-label nil)
              (->> do-query
                   (map #(-> %
                             (update :updated-time tc/to-epoch)
                             (update :confirm-time tc/to-epoch)))
                   (group-by :article-id)
                   (u/map-values #(do {:labels %}))))
          amap
          (->> (merge-with merge articles alabels)
               (u/map-values
                (fn [article]
                  (let [updated-time
                        (->> (:labels article)
                             (map :updated-time)
                             (apply max 0))]
                    (merge article
                           {:updated-time updated-time})))))]
      amap)))

(def sort-article-id #(sort-by :article-id < %))

(def filter-has-labels #(not-empty (:labels %)))

(def filter-has-confirmed-labels
  #(->> % :labels (filter :confirm-time) not-empty))

(defn filter-has-user-labels [user-id]
  (fn [article]
    (->> article
         :labels
         (filter #(= (:user-id %) user-id))
         not-empty)))

;; TODO: include user notes in search
(defn filter-free-text-search [text]
  (fn [article]
    (let [tokens (-> text (str/lower-case) (str/split #"[ \t\r\n]+"))
          all-article-text (->> [(:primary-title article)
                                 (:secondary-title article)
                                 (str/join "\n" (:authors article))
                                 (:abstract article)]
                                (str/join "\n")
                                (str/lower-case))]
      (every? #(str/includes? all-article-text %) tokens))))

(defn filter-has-label-id
  [label-id &
   {:keys [require-confirmed?]
    :or {require-confirmed? false}}]
  (fn [article]
    (->> article
         :labels
         (filter #(if require-confirmed?
                    (:confirm-time %)
                    true))
         (filter #(= (:label-id %) label-id))
         not-empty)))

(defn query-project-article-list
  [project-id {:keys [filters sort-fn n-offset n-count]
               :or {filters []
                    sort-fn sort-article-id
                    n-offset 0
                    n-count 10}}]
  (let [filter-all-fn (if (empty? filters)
                        (constantly true)
                        (apply every-pred filters))
        entries (->> (vals (project-full-article-list project-id))
                     (filter filter-all-fn)
                     (sort-fn))]
    {:entries (->> entries (drop n-offset) (take n-count))
     :total-count (count entries)}))

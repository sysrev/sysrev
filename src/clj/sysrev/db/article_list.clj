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

#_
(def filter-has-confirmed-labels
  #(->> % :labels (filter :confirm-time) not-empty))

(defn filter-has-user [{:keys [user content confirmed]}]
  (fn [article]
    (let [labels (cond->> (:labels article)
                   ;; TODO: filter confirmed
                   user (filter #(= (:user-id %) user)))
          ;; TODO: search annotations
          annotations nil]
      (or (not-empty labels)
          (not-empty annotations)))))

#_
(defn filter-has-content [{:keys []}]
  nil)

#_
(defn filter-has-labels [{:keys []}]
  nil)

#_
(defn filter-has-annotations [{:keys []}]
  nil)

#_
(defn filter-by-inclusion [{:keys []}]
  nil)

#_
(defn filter-by-consensus [{:keys []}]
  nil)

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

(defn get-sort-fn [sort-by]
  (case sort-by
    :article-id sort-article-id
    sort-article-id))

(defn get-filter-fn [fmap]
  (let [filter-name (first (keys fmap))
        make-filter
        (case filter-name
          :has-user          filter-has-user
          :text-search       filter-free-text-search
          (constantly true))]
    (make-filter (get fmap filter-name))))

(defn project-article-list-filtered [project-id filters sort-by]
  (with-project-cache
    project-id [:filtered-article-list [sort-by filters]]
    (let [sort-fn (get-sort-fn sort-by)
          filter-fns (mapv get-filter-fn filters)
          filter-all-fn (if (empty? filters)
                          (constantly true)
                          (apply every-pred filter-fns))]
      (->> (vals (project-full-article-list project-id))
           (filter filter-all-fn)
           (sort-fn)))))

(defn query-project-article-list
  [project-id {:keys [filters sort-by n-offset n-count]
               :or {filters []
                    sort-by :article-id
                    n-offset 0
                    n-count 20}}]
  (let [entries (project-article-list-filtered project-id filters sort-by)]
    {:entries (->> entries (drop n-offset) (take n-count))
     :total-count (count entries)}))

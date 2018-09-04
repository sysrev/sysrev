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
            [sysrev.db.project :as project]
            [sysrev.db.labels :as labels]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :as u :refer [in? map-values]]
            [sysrev.shared.article-list :as al]
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
                   (map-values first)))
          alabels
          (-> (q/select-project-articles
               project-id [:al.article-id :al.label-id :al.user-id
                           :al.answer :al.inclusion :al.updated-time
                           :al.confirm-time :al.resolve])
              (q/join-article-labels)
              (q/filter-valid-article-label nil)
              (->> do-query
                   (remove #(or (nil? (:answer %))
                                (and (sequential? (:answer %))
                                     (empty? (:answer %)))))
                   (map #(-> %
                             (update :updated-time tc/to-epoch)
                             (update :confirm-time tc/to-epoch)))
                   (group-by :article-id)
                   (map-values #(do {:labels %}))))
          anotes
          (-> (q/select-project-articles
               project-id [:a.article-id :an.user-id  :an.content
                           :an.updated-time :pn.name])
              (q/with-article-note)
              (->> do-query
                   (remove (fn [{:keys [content]}]
                             (or (nil? content)
                                 (and (string? content)
                                      (empty? (str/trim content))))))
                   (map #(-> % (update :updated-time tc/to-epoch)))
                   (group-by :article-id)
                   (map-values #(do {:notes %}))))
          amap
          (->> (merge-with merge articles alabels anotes)
               (map-values
                (fn [article]
                  (let [updated-time
                        (->> [(->> (:labels article)
                                   (map :updated-time))
                              (->> (:notes article)
                                   (map :updated-time))]
                             (apply concat)
                             (remove nil?)
                             (apply max 0))]
                    (merge article
                           {:updated-time updated-time})))))]
      amap)))

(defn sort-article-id [sort-dir]
  (let [dir (if (= sort-dir :desc) > <)]
    #(sort-by :article-id dir %)))

(defn sort-updated-time [sort-dir]
  (fn [entries]
    (cond-> (sort-by (fn [{:keys [updated-time article-id]}]
                       [updated-time article-id])
                     entries)
      (= sort-dir :desc) reverse)))

#_
(def filter-has-confirmed-labels
  #(->> % :labels (filter :confirm-time) not-empty))

(defn filter-has-user [context {:keys [user content confirmed]}]
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

(defn filter-by-consensus [context {:keys [status inclusion]}]
  (let [{:keys [project-id]} context
        overall-id (project/project-overall-label-id project-id)]
    (fn [{:keys [labels] :as article}]
      (let [overall
            (->> labels
                 (filter #((every-pred (comp not nil?)
                                       (comp not zero?))
                           (:confirm-time %)))
                 (filter #(= (:label-id %) overall-id)))
            status-test
            (case status
              :single al/is-single?
              :determined #(or (al/is-resolved? %)
                               (al/is-consistent? %))
              :conflict al/is-conflict?
              :consistent al/is-consistent?
              :resolved al/is-resolved?
              (constantly true))
            inclusion-test
            (if (nil? inclusion)
              (constantly true)
              (fn []
                (let [entries (if (or (= status :resolved)
                                      (and (= status :determined)
                                           (al/is-resolved? overall)))
                                (filter :resolve overall)
                                overall)]
                  (in? (->> entries (map :inclusion) distinct)
                       inclusion))))]
        (and (not-empty overall)
             (status-test overall)
             (inclusion-test))))))

;; TODO: include user notes in search
(defn filter-free-text-search [context text]
  (fn [article]
    (let [tokens (-> text (str/lower-case) (str/split #"[ \t\r\n]+"))
          all-article-text (->> [(:primary-title article)
                                 (:secondary-title article)
                                 (str/join "\n" (:authors article))
                                 (:abstract article)]
                                (str/join "\n")
                                (str/lower-case))]
      (every? #(str/includes? all-article-text %) tokens))))

(defn get-sort-fn [sort-by sort-dir]
  (case sort-by
    :article-added (sort-article-id sort-dir)
    :content-updated (sort-updated-time sort-dir)
    (sort-article-id sort-dir)))

(defn get-filter-fn [context]
  (fn [fmap]
    (let [filter-name (first (keys fmap))
          make-filter
          (case filter-name
            :has-user          filter-has-user
            :text-search       filter-free-text-search
            :consensus         filter-by-consensus
            (constantly (constantly true)))]
      (make-filter context (get fmap filter-name)))))

(defn project-article-list-filtered
  [{:keys [project-id] :as context} filters sort-by sort-dir]
  (with-project-cache
    project-id [:filtered-article-list [filters sort-by sort-dir]]
    (let [sort-fn (get-sort-fn sort-by sort-dir)
          filter-fns (mapv (get-filter-fn context) filters)
          filter-all-fn (if (empty? filters)
                          (constantly true)
                          (apply every-pred filter-fns))]
      (->> (vals (project-full-article-list project-id))
           (filter filter-all-fn)
           (sort-fn)))))

(defn query-project-article-list
  [project-id {:keys [filters sort-by sort-dir n-offset n-count user-id]
               :or {filters []
                    sort-by :content-updated
                    sort-dir :desc
                    n-offset 0
                    n-count 20
                    user-id nil}}]
  (let [context {:project-id project-id :user-id user-id}
        entries (project-article-list-filtered
                 context filters sort-by sort-dir)]
    {:entries (->> entries (drop n-offset) (take n-count))
     :total-count (count entries)}))

(ns sysrev.db.export
  (:require
   [clojure.string :as str]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [sysrev.db.core :refer [do-query]]
   [sysrev.db.queries :as q]
   [sysrev.shared.util :refer [map-values in?]]))

(defn export-project [project-id]
  (let [articles
        (-> (q/select-project-articles
             project-id [:article-id :primary-title :secondary-title :authors :abstract
                         :year :urls :keywords :remote-database-name :work-type])
            (->> do-query
                 (group-by :article-id)
                 (map-values first)
                 (map-values #(-> % (assoc :locations {}
                                           :user-labels {}
                                           :user-notes {}
                                           :title (:primary-title %)
                                           :journal (:secondary-title %))
                                  (dissoc :primary-title :secondary-title)))))
        all-article-ids (apply hash-set (keys articles))
        alocations
        (-> (q/select-project-articles
             project-id [:aloc.article-id :aloc.source :aloc.external-id])
            (q/join-article-locations)
            (->> do-query
                 (filter #(contains? all-article-ids (:article-id %)))
                 (group-by :article-id)
                 (map-values
                  (fn [xs] (->> xs (map #(-> % (dissoc :article-id))))))
                 (map-values #(merge {} {:locations %}))))
        anotes
        (-> (q/select-project-articles
             project-id [:an.article-id :an.user-id :an.content :an.updated-time])
            (q/with-article-note "default")
            (->> do-query
                 (filter #(contains? all-article-ids (:article-id %)))
                 (group-by :article-id)
                 (map-values
                  (fn [xs] (->> xs (map #(-> % (dissoc :article-id))))))
                 (map-values #(merge {} {:user-notes %}))))
        ldefs-vec
        (-> (q/select-label-where
             project-id nil [:l.label-id :l.label-id-local :l.name :l.question
                             :l.short-label :l.value-type :l.required :l.category
                             :l.definition])
            (->> do-query vec))
        l-uuid-to-int
        (->> ldefs-vec
             (group-by :label-id)
             (map-values first)
             (map-values :label-id-local))
        ldefs-map
        (->> ldefs-vec
             (group-by :label-id-local)
             (map-values first)
             (map-values #(let [int-id (:label-id-local %)]
                            (-> % (dissoc :label-id :label-id-local)
                                (assoc :label-id int-id)))))
        alabels
        (-> (q/select-project-articles
             project-id [:al.article-id :al.label-id :al.user-id :al.answer :al.inclusion
                         :al.resolve :al.updated-time])
            (q/join-article-labels)
            (q/filter-valid-article-label true)
            (->> do-query
                 (filter #(contains? all-article-ids (:article-id %)))
                 (group-by :article-id)
                 (map-values
                  (fn [xs] (->> xs (map #(-> % (dissoc :article-id)
                                             (update :label-id l-uuid-to-int))))))
                 (map-values #(merge {} {:user-labels %}))))
        users
        (-> (q/select-project-members
             project-id [:u.user-id :u.email :u.admin :u.permissions])
            (->> do-query
                 (remove #(or (:admin %)
                              (in? (:permissions %) "admin")))
                 (map #(select-keys % [:user-id :email]))
                 (sort-by :user-id <)))]
    {:articles (->> (merge-with merge articles alocations alabels anotes)
                    vals (sort-by :article-id <))
     :project-labels (->> ldefs-map vals (sort-by :label-id <))
     :users users
     :version "1.0.1"}))

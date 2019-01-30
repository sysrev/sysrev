(ns sysrev.label.migrate
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :as project]
            [sysrev.label.answer :as answer]
            [sysrev.article.core :as article]
            [sysrev.shared.util :refer [map-values in?]]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]))

;;;
;;; article_resolve migration
;;;

(defn project-resolved-articles
  "(DB format migration) Get sequence of article ids in project that have
  resolved answers based on old-format article_label entries."
  [project-id]
  (-> (select :%distinct.a.article-id)
      (from [:article-label :al])
      (join [:article :a] [:= :a.article-id :al.article-id])
      (where [:and
              [:= :a.project-id project-id]
              [:= :resolve true]])
      (->> do-query (mapv :article-id))))

(defn article-resolve-info
  "(DB format migration) Get map of resolved answer status for
  article-id based on old-format article_label entries."
  [article-id]
  (-> (select :article-id :user-id :updated-time)
      (from :article-label)
      (where [:= :article-id article-id])
      (order-by [:updated-time :desc])
      (limit 1)
      do-query first))

(defn project-has-article-resolve?
  "(DB format migration) Test for presence of new-format article_resolve
  entries in project."
  [project-id]
  (-> (select :%count.%distinct.aresolve.article-id)
      (from [:article-resolve :aresolve])
      (join [:article :a] [:= :a.article-id :aresolve.article-id])
      (where [:= :a.project-id project-id])
      do-query first :count pos-int?))

(defn migrate-project-article-resolve
  "Create article_resolve entries in project-id using old-format values
  from article_label."
  [project-id]
  (with-transaction
    (when-not (project-has-article-resolve? project-id)
      (let [overall-id (project/project-overall-label-id project-id)
            article-ids (try
                          (project-resolved-articles project-id)
                          (catch Throwable e
                            (log/info "unable to query on article.resolve")
                            nil))]
        (when (not-empty article-ids)
          (doseq [article-id article-ids]
            (let [{:keys [user-id updated-time] :as e}
                  (article-resolve-info article-id)]
              (when e
                (answer/resolve-article-answers
                 article-id user-id :resolve-time updated-time)))))))))

(defn projects-with-resolve
  "(DB format migration) Get project ids with resolved articles based on
  old-format article_label entries."
  []
  (-> (select :%distinct.a.project-id)
      (from [:article-label :al])
      (join [:article :a] [:= :a.article-id :al.article-id])
      (where [:= :al.resolve true])
      do-query))

(defn migrate-all-project-article-resolve
  "Create article_resolve entries for all projects."
  []
  (with-transaction
    (doseq [project-id (projects-with-resolve)]
      (migrate-project-article-resolve project-id))))

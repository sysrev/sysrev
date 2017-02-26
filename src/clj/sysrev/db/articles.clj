(ns sysrev.db.articles
  (:require
   [clojure.spec :as s]
   [clojure.java.jdbc :as j]
   [sysrev.shared.util :as u]
   [sysrev.shared.spec.core :as sc]
   [sysrev.shared.spec.article :as sa]
   [sysrev.db.core :as db :refer
    [do-query do-execute to-sql-array sql-now with-project-cache
     clear-project-cache]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [honeysql-postgres.format :refer :all]
   [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
   [sysrev.db.queries :as q]))

(defn article-to-sql
  "Converts some fields in an article map to values that can be passed
  to honeysql and JDBC."
  [article & [conn]]
  (-> article
      (update :authors #(to-sql-array "text" % conn))
      (update :keywords #(to-sql-array "text" % conn))
      (update :urls #(to-sql-array "text" % conn))
      (update :document-ids #(to-sql-array "text" % conn))))
;;
(s/fdef article-to-sql
        :args (s/cat :article ::sa/article-partial
                     :conn (s/? any?))
        :ret map?)

(defn add-article [article project-id]
  (let [project-id (q/to-project-id project-id)]
    (-> (insert-into :article)
        (values [(merge (article-to-sql article)
                        {:project-id project-id})])
        (returning :article-id)
        do-query first :article-id)))
;;
(s/fdef add-article
        :args (s/cat :article ::sa/article-partial
                     :project-id ::sc/project-id)
        :ret (s/nilable ::sc/article-id))

(defn set-user-article-note [article-id user-id note-name content]
  (let [article-id (q/to-article-id article-id)
        user-id (q/to-user-id user-id)
        note-name (name note-name)
        pnote (-> (q/select-article-by-id article-id [:pn.*])
                  (merge-join [:project :p]
                              [:= :p.project-id :a.project-id])
                  (q/with-project-note note-name)
                  do-query first)
        anote (-> (q/select-article-by-id article-id [:an.*])
                  (q/with-article-note note-name user-id)
                  do-query first)]
    (assert pnote "note type not defined in project")
    (assert (:project-id pnote) "project-id not found")
    (clear-project-cache (:project-id pnote) [:article article-id :notes])
    (clear-project-cache (:project-id pnote) [:users user-id :notes])
    (let [fields {:article-id article-id
                  :user-id user-id
                  :project-note-id (:project-note-id pnote)
                  :content content
                  :updated-time (sql-now)}]
      (if (nil? anote)
        (-> (sqlh/insert-into :article-note)
            (values [fields])
            (returning :*)
            do-query)
        (-> (sqlh/update :article-note)
            (where [:and
                    [:= :article-id article-id]
                    [:= :user-id user-id]
                    [:= :project-note-id (:project-note-id pnote)]])
            (sset fields)
            (returning :*)
            do-query)))))
;;
(s/fdef set-user-article-note
        :args (s/cat :article-id ::sc/article-id
                     :user-id ::sc/user-id
                     :note-name (s/or :s string?
                                      :k keyword?)
                     :content (s/nilable string?))
        :ret (s/nilable map?))

(defn article-user-notes-map [project-id article-id]
  (let [project-id (q/to-project-id project-id)
        article-id (q/to-article-id article-id)]
    (with-project-cache
      project-id [:article article-id :notes :user-notes-map]
      (->>
       (-> (q/select-article-by-id article-id [:an.* :pn.name])
           (q/with-article-note)
           do-query)
       (group-by :user-id)
       (u/map-values
        #(->> %
              (group-by :name)
              (u/map-values first)
              (u/map-values :content)))))))
;;
(s/fdef article-user-notes-map
        :args (s/cat :project-id ::sc/project-id
                     :article-id ::sc/article-id)
        :ret (s/nilable map?))

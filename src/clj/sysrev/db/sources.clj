(ns sysrev.db.sources
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [honeysql.helpers :as sqlh :refer [values insert-into where sset from select
                                               delete-from merge-where group left-join]]
            [honeysql-postgres.helpers :refer [returning]]
            [sysrev.db.core :refer [do-query do-execute clear-query-cache with-transaction]]
            [sysrev.db.project :as project]))

(defn create-project-source-metadata!
  "Create a project_source table entry with a metadata map for project-id"
  [project-id metadata]
  (-> (insert-into :project_source)
      (values [{:meta metadata
                :project-id project-id}])
      (returning :source_id)
      do-query first :source-id))
;;
(s/fdef create-project-source-metadata!
        :args (s/cat :project-id int?
                     :metadata map?)
        :ret int?)

(def import-pmids-meta
  {:source "PMID vector"})

(defn import-pmids-from-filename-meta
  [filename]
  {:source "PMID file"
   :filename filename})

(defn import-articles-from-endnote-file-meta
  [filename]
  {:source "EndNote file"
   :filename filename})

(defn import-pmids-search-term-meta
  [search-term]
  {:source "PubMed search"
   :search-term search-term})
;;
(s/fdef import-pmids-search-term-meta
        :args (s/cat :search-term string?)
        :ret map?)

(def import-facts-meta
  {:source "facts"})

(defn update-project-source-metadata!
  "Replace the metadata for project-source-id"
  [project-source-id metadata]
  (-> (sqlh/update :project_source)
      (sset {:meta metadata})
      (where [:= :source_id project-source-id])
      do-execute))
;;
(s/fdef update-project-source-metadata!
        :args (s/cat :project-source-id int?
                     :metadata map?))

(defn alter-project-source-metadata!
  "Replaces the meta field for project-source-id with the result of
  applying function f to the existing value."
  [project-source-id f]
  (when-let [meta (-> (select :meta)
                      (from :project-source)
                      (where [:= :source-id project-source-id])
                      do-query first :meta)]
    (update-project-source-metadata! project-source-id (f meta))))
;;
(s/fdef alter-project-source-metadata!
        :args (s/cat :project-source-id int?
                     :f ifn?))

(defn delete-project-source-articles!
  "Deletes all article-source entries for source-id, and their associated
  article entries unless contained in another source."
  [source-id]
  (clear-query-cache)
  (with-transaction
    (-> (delete-from [:article :a])
        (where (let [asources
                     (-> (select :*)
                         (from [:article-source :asrc])
                         (where [:= :asrc.article-id :a.article-id]))]
                 [:and
                  [:exists
                   (-> asources
                       (merge-where
                        [:= :asrc.source-id source-id]))]
                  [:not
                   [:exists
                    (-> asources
                        (merge-where
                         [:!= :asrc.source-id source-id]))]]]))
        do-execute)
    (-> (delete-from :article-source)
        (where [:= :source-id source-id])
        do-execute)))

(defn fail-project-source-import!
  "Update database in response to an error during the import process
  for a project-source."
  [source-id]
  (alter-project-source-metadata!
   source-id #(assoc % :importing-articles? :error))
  (delete-project-source-articles! source-id))

(defn delete-project-source!
  "Given a source-id, delete it and remove the articles associated with
  it from the database.  Warning: This fn doesn't care if there are
  labels associated with an article"
  [source-id]
  (clear-query-cache)
  (alter-project-source-metadata!
   source-id #(assoc % :deleting? true))
  (try (with-transaction
         ;; delete articles that aren't contained in another source
         (delete-project-source-articles! source-id)
         ;; delete entries for project source
         (-> (delete-from :project-source)
             (where [:= :source-id source-id])
             do-execute)
         true)
       (catch Throwable e
         (log/info "Caught exception in sysrev.db.project/delete-project-source!: "
                   (.getMessage e))
         (alter-project-source-metadata!
          source-id #(assoc % :deleting? false))
         false)))
;;
(s/fdef delete-project-source!
        :args (s/cat :source-id int?))

(defn source-articles-with-labels
  "Given a source-id, return the amount of articles that have labels"
  [source-id]
  (-> (select :%count.*)
      (from :article-label)
      (where [:in :article_id
              (-> (select :article_id)
                  (from :article_source)
                  (where [:= :source_id source-id]))])
      do-query first :count))
;;
(s/fdef source-articles-with-labels
        :args (s/cat :project-id int?)
        :ret (s/nilable int?))

(defn project-sources
  "Given a project-id, return the corresponding vectors of
  project-source data or nil if it does not exist"
  [project-id]
  (when (not (project/project-exists? project-id))
    (throw (Exception. (str "No project with project-id: " project-id))))
  (-> (select :ps.source-id
              :ps.project-id
              :ps.meta
              :ps.date-created
              [:%count.ars.source_id "article-count"])
      (from [:project_source :ps])
      (left-join [:article_source :ars] [:= :ars.source_id :ps.source_id])
      (group :ps.source_id)
      (where [:= :ps.project_id project-id])
      (->>
       do-query
       (mapv #(assoc % :labeled-article-count
                     (source-articles-with-labels (:source-id %)))))))
;;
(s/fdef project-sources
        :args (s/cat :project-id int?)
        :ret (s/nilable vector?))

(defn source-has-labeled-articles?
  [source-id]
  (boolean (> (source-articles-with-labels source-id)
              0)))
;;
(s/fdef source-has-labeled-articles?
        :args (s/cat :source-id int?)
        :ret boolean?)

(defn add-article-to-source!
  "Add article-id to source-id"
  [article-id source-id]
  (-> (sqlh/insert-into :article_source)
      (values [{:article_id article-id
                :source_id source-id}])
      do-execute))


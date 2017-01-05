(ns sysrev.db.documents
  (:require
   [sysrev.util :refer [map-values]]
   [sysrev.db.core :refer
    [do-query do-execute do-transaction]]
   [honeysql.core :as sql]
   [honeysql.helpers :as sqlh :refer :all :exclude [update]]
   [me.raynes.fs :as fs]
   [clojure.java.io :as io]))

(defn load-article-documents
  "Loads and replaces the database entries for article PDF files based on the
  files present in directory `pdfs-path`."
  [pdfs-path]
  (let [doc-type "article-pdf"
        doc-files (->> (fs/list-dir pdfs-path)
                       (map (fn [doc-id-path]
                              (let [doc-id (fs/base-name doc-id-path)
                                    files (fs/list-dir doc-id-path)]
                                [doc-id files])))
                       (apply concat)
                       (apply hash-map))
        db-entries (->> doc-files
                        (map (fn [[doc-id files]]
                               (->> files
                                    (map #(when (fs/file? %)
                                            (let [fname (fs/base-name %)]
                                              {:document-id doc-id
                                               :document-type doc-type
                                               :fs-path fname})))
                                    (remove nil?))))
                        (apply concat))]
    (do
      (do-transaction
       nil
       (-> (delete-from :document)
           (where [:= :document-type doc-type])
           do-execute)
       (-> (insert-into :document)
           (values db-entries)
           do-execute))
      (let [dcount (-> (select :%count.*)
                       (from :document)
                       (where [:= :document-type doc-type])
                       do-query first :count)]
        (->> dcount (format "loaded %s article documents") println))
      nil)))

(defn all-article-document-paths []
  (->>
   (-> (select :document-id :fs-path)
       (from :document)
       (where [:= :document-type "article-pdf"])
       do-query)
   (group-by :document-id)
   (map-values #(mapv :fs-path %))))

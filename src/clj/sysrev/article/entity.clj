;;;
;;; NOTE: this is not yet used - intended to replace
;;; sysrev.article.core/get-article with generalized mechanism
;;;

(ns sysrev.article.entity
  (:require [sysrev.article.core :as a]
            [sysrev.db.files :as files]
            [sysrev.entity :as e]))

(e/def-entity :article {:primary-key :article-id})

(e/def-entity-value :article :pdfs
  (fn [article-id]
    (let [pmcid-s3store-id (some-> article-id a/article-pmcid a/pmcid->s3-id)]
      (->> (files/get-article-file-maps article-id)
           (mapv #(assoc % :open-access?
                         (= (:id %) pmcid-s3store-id)))))))

;;;
;;; NOTE: this is not yet used - intended to replace
;;; sysrev.article.core/get-article with generalized mechanism
;;;

(ns sysrev.article.entity
  (:require [sysrev.article.core :as a]
            [sysrev.file.article :as article-file]
            [sysrev.entity :as e]))

(e/def-entity :article {:primary-key :article-id})

(e/def-entity-value :article :pdfs
  (fn [article-id]
    (let [pmcid-s3-id (some-> article-id a/article-pmcid article-file/pmcid->s3-id)]
      (->> (article-file/get-article-file-maps article-id)
           (mapv #(assoc % :open-access?
                         (= (:s3-id %) pmcid-s3-id)))))))

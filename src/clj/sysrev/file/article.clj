(ns sysrev.file.article
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.queries :as q]
            [sysrev.file.core :as file]
            [sysrev.util :as util]))

(defn-spec article-pdf-associated? boolean?
  "Check if article-pdf association exists between s3-id, article-id."
  [s3-id (s/nilable ::file/s3-id), article-id (s/nilable int?)]
  (boolean (and s3-id article-id
                (q/find-one :article-pdf {:s3-id s3-id :article-id article-id}))))

(defn-spec associate-article-pdf (s/nilable int?)
  "Associate an s3 filename/key pair with an article."
  [s3-id ::file/s3-id, article-id int?]
  (when-not (article-pdf-associated? s3-id article-id)
    (q/create :article-pdf {:article-id article-id :s3-id s3-id})))

(defn-spec dissociate-article-pdf int?
  "Remove any article-pdf association between s3-id and article-id."
  [s3-id ::file/s3-id, article-id int?]
  (q/delete :article-pdf {:article-id article-id :s3-id s3-id}))

(defn get-article-file-maps
  "Returns a coll of s3store file maps associated with article-id."
  [article-id]
  (q/find [:s3store :s3]
          {:s3.s3-id (q/find [:article-pdf :apdf] {:apdf.article-id article-id}
                             :apdf.s3-id, :return :query)}
          [:s3.s3-id :s3.key :s3.filename]))

(defn save-article-pdf [sr-context {:keys [article-id filename file file-bytes]}]
  (util/assert-single file file-bytes)
  (let [{:keys [key s3-id]}
        (file/save-s3-file
         sr-context
         :pdf filename (or (some->> file (hash-map :file))
                           (some->> file-bytes (hash-map :file-bytes))))]
    (associate-article-pdf s3-id article-id)
    {:article-id article-id :filename filename :s3-id s3-id :key key}))

(defn pmcid->s3-id [pmcid]
  (q/find-one :pmcid-s3store {:pmcid pmcid} :s3-id))

(defn associate-pmcid-s3store [pmcid s3-id]
  (q/create :pmcid-s3store {:pmcid pmcid :s3-id s3-id}))

(ns sysrev.datasource.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.util :as util :refer [parse-integer]]))

(s/def ::article-type string?)
(s/def ::article-subtype (s/nilable string?))
(s/def ::datasource-name (s/nilable string?))
(s/def ::external-id (s/nilable any?))
(s/def ::title string?)
(s/def ::content (s/nilable any?))

(util/defspec-keys+partial ::article-data ::article-data-partial
  [::article-type
   ::article-subtype
   ::datasource-name
   ::external-id
   ::title
   ::content])

(defn match-existing-article-data
  "Returns `article-data-id` value for an existing `article-data` entry
  that matches argument, or nil if no entry exists.

  Updates the title if it differs."
  [{:keys [datasource-name external-id title] :as _article-data}]
  (when (and datasource-name external-id)
    (let [existing (q/find-one :article-data {:datasource-name datasource-name
                                              :external-id (db/to-jsonb external-id)}
                               [:article-data-id :title])]
      (if (= title (:title existing))
        (:article-data-id existing)
        (first
         (q/modify :article-data
                   {:datasource-name datasource-name
                    :external-id (db/to-jsonb external-id)}
                   {:title title}
                   :returning :article-data-id))))))

(defn save-article-data
  "Returns `article-data-id` value for entry `article-data`, creating
  entry first if no matching entry exists."
  [{:keys [article-type article-subtype datasource-name external-id title content]
    :as article-data}]
  (db/with-transaction
    (or (match-existing-article-data article-data)
        (q/create :article-data (-> article-data
                                    (update :content #(some-> % db/to-jsonb))
                                    (update :external-id #(some-> % db/to-jsonb)))
                  :returning :article-data-id))))

(defn datasource-name-for-type [{:keys [article-type article-subtype] :as _types}]
  (condp = article-type
    "academic" (condp = article-subtype
                 "pubmed"   "pubmed"
                 "RIS" "RIS"
                 nil)
    "json" (condp = article-subtype
             "ctgov" "ctgov")
    "pdf" (condp = article-subtype
            "fda-drugs-docs" "fda-drugs-docs")
    "datasource" "entity"
    nil))

(defn make-article-data
  [{:keys [article-type article-subtype] :as extra}
   {:keys [content external-id helper-text primary-title public-id]}]
  ;; allow alternate :public-id key to support legacy article migration
  (let [external-id (or external-id public-id)
        datasource-name (datasource-name-for-type extra)]
    {:article-type article-type
     :article-subtype article-subtype
     :datasource-name datasource-name
     :external-id (when datasource-name
                    (if (= datasource-name "pubmed")
                      (some-> external-id parse-integer str)
                      external-id))
     :title primary-title
     :content content}))

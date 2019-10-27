(ns sysrev.datasource.core
  (:require [clojure.spec.alpha :as s]
            #_ [orchestra.core :refer [defn-spec]]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :as sutil :refer [parse-integer]]))

(s/def ::article-type string?)
(s/def ::article-subtype (s/nilable string?))
(s/def ::datasource-name (s/nilable string?))
(s/def ::external-id (s/nilable any?))
(s/def ::title string?)
(s/def ::content (s/nilable any?))

(sutil/defspec-keys+partial ::article-data ::article-data-partial
  [::article-type
   ::article-subtype
   ::datasource-name
   ::external-id
   ::title
   ::content])

(defn match-existing-article-data
  "Returns `article-data-id` value for an existing `article-data` entry
  that matches argument, or nil if no entry exists."
  [{:keys [datasource-name external-id] :as _article-data}]
  (when (and datasource-name external-id)
    (q/find-one :article-data {:datasource-name datasource-name
                               :external-id (db/to-jsonb external-id)}
                :article-data-id)))

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

(defn project-source-meta->article-type [meta]
  (condp = (:source meta)
    "PubMed search"    ["academic"  "pubmed"]
    "PMID vector"      ["academic"  "pubmed"]
    "PMID file"        ["academic"  "pubmed"]
    "EndNote file"     ["academic"  "endnote"]
    "PDF Zip file"     ["file"      "pdf"]
    "API Text Manual"  ["text"      "generic"]
    "legacy"           ["academic"  "unknown"]
    nil))

(defn datasource-name-for-type [{:keys [article-type article-subtype] :as _types}]
  (condp = article-type
    "academic"   (condp = article-subtype
                   "pubmed"   "pubmed"
                   nil)
    nil))

(defn make-article-data
  [{:keys [article-type article-subtype] :as extra}
   {:keys [public-id external-id primary-title] :as article}]
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
     :content (when-not datasource-name
                (->> (dissoc article
                             :source-meta :text-search :enabled :article-data-id
                             :article-id :article-uuid :parent-article-uuid)
                     (sutil/filter-values (comp not nil?))))}))

(defn copy-legacy-article-content [article-id]
  (db/with-transaction
    (when-let [article (first (q/find [:article :a] {:a.article-id article-id}
                                      [:a.* [:ps.meta :source-meta]]
                                      :join [[:article-source:as :a.article-id]
                                             [:project-source:ps :as.source-id]]))]
      (let [[article-type article-subtype]
            (project-source-meta->article-type (:source-meta article))]
        (assert (and article-type article-subtype)
                (pr-str (:source-meta article)))
        (assert (:primary-title article))
        (let [article-data-id (-> (make-article-data {:article-type article-type
                                                      :article-subtype article-subtype}
                                                     article)
                                  (save-article-data))]
          (q/modify :article {:article-id article-id} {:article-data-id article-data-id}))))))

;; note: projects #2062 and #3440 have article entries with no article-source somehow
#_ (q/find [:article :a] {} :article-id
           :where (q/not-exists [:article-source :as] {:as.article-id :a.article-id})
           :group-by :project-id)

(defn migrate-article-data
  "Runs migration to update `article` table format by creating
  `article-data` entries and linking to `article`.

  Procedure:
  1. Apply flyway migration V0.0153__article_data.sql
  2. Run this function
  3. Apply flyway migration V0.0154__remove_article_fields.sql"
  []
  (let [article-ids (q/find :article {:article-data-id nil} :article-id)]
    (log/infof "migrating data for %d articles" (count article-ids))
    (->> (map-indexed vector article-ids)
         (pmap (fn [[i article-id]]
                 (when (and (pos? i) (zero? (mod i 10000)))
                   (log/infof "copying article #%d" i))
                 (try (copy-legacy-article-content article-id)
                      (catch Throwable _
                        (log/infof "exception on article-id=%d, retrying..." article-id)
                        (Thread/sleep 250)
                        (try (copy-legacy-article-content article-id)
                             (log/infof "success on retry for article-id=%d" article-id)
                             (catch Throwable e2
                               (log/warnf "copy failed for article-id=%d" article-id)
                               (log/warn (.getMessage e2))))))
                 nil))
         doall)
    (log/infof "migrate finished - %d articles not linked to article-data"
               (q/find-count :article {:article-data-id nil}))
    nil))

(defn pubmed-data? [{:keys [datasource-name external-id] :as _article-data}]
  (boolean (and (= datasource-name "pubmed")
                (parse-integer external-id))))

(defn delete-unlinked-article-data []
  (q/delete [:article-data :ad] {}
            :where (q/not-exists [:article :a] {:a.article-data-id :ad.article-data-id})))

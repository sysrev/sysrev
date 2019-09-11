(ns sysrev.datasource.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :as sutil :refer [parse-integer]]))

(defn match-existing-article-data
  "Returns `article-data-id` value for an existing `article-data` entry
  that matches `fields`, or nil if no entry exists."
  [{:keys [datasource-name external-id] :as article-data}]
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
        (q/create :article-data (-> (update article-data :content #(some-> % db/to-jsonb))
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

(defn make-article-data
  [{:keys [article-type article-subtype] :as extra}
   {:keys [public-id primary-title] :as article}]
  {:article-type article-type
   :article-subtype article-subtype
   :datasource-name (when (= article-subtype "pubmed") "pubmed")
   :external-id (when (= article-subtype "pubmed")
                  (some-> public-id parse-integer str))
   :title primary-title
   :content (when (not= article-subtype "pubmed")
              (dissoc article :source-meta :text-search :enabled :article-data-id
                      :article-id :article-uuid :parent-article-uuid :raw))})

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
        (let [article-data-id (save-article-data
                               (make-article-data {:article-type article-type
                                                   :article-subtype article-subtype}
                                                  article))]
          (q/modify :article {:article-id article-id} {:article-data-id article-data-id}))))))

;; note: projects #2062 and #3440 have article entries with no article-source somehow
#_ (q/find [:article :a] {} :article-id
           :where (q/not-exists [:article-source :as] {:as.article-id :a.article-id})
           :group-by :project-id)
(defn migrate-article-data []
  (let [article-ids (q/find :article {:article-data-id nil} :article-id)]
    (log/infof "migrating data for %d articles" (count article-ids))
    (->> (map-indexed (fn [i article-id] [i article-id]) article-ids)
         (map (fn [[i article-id]]
                (when (zero? (mod i 10000))
                  (log/infof "copying article #%d...." i))
                (try (copy-legacy-article-content article-id)
                     (catch Throwable e
                       (log/warnf "got error on article-id=%d" article-id)
                       (log/warn (.getMessage e))))))
         doall)
    nil))

(defn migrate-article-data-public-ids
  "Populate `external-id` values for PubMed articles in `article-data` using
  values from `:public-id` field in `content`."
  []
  (doseq [ids (->> (q/find :article-data {:article-type "academic" :article-subtype "pubmed"
                                          :external-id nil}
                           :article-data-id)
                   (partition-all 100))]
    (doseq [{:keys [article-data-id content]}
            (q/find :article-data {:article-data-id ids} [:article-data-id :content])]
      (when-let [public-id (some-> content :public-id parse-integer)]
        (q/modify :article-data {:article-data-id article-data-id}
                  {:external-id (-> public-id str db/to-jsonb)})))))

(defn migrate-datasource-name []
  (q/modify :article-data {:article-type "academic" :article-subtype "pubmed"
                           :datasource-name nil}
            {:datasource-name "pubmed"}))

(defn migrate-article-data-duplicates []
  (doseq [datasource-name (q/find :article-data {} [[:%distinct.datasource-name :x]]
                                  :where [:!= :datasource-name nil])]
    (let [id-map (->> (q/find :article-data {:datasource-name datasource-name} :article-data-id
                              :group-by :external-id, :where [:!= :external-id nil])
                      (sutil/filter-values #(>= (count %) 2)))]
      (when (seq id-map)
        (log/infof "merging %d article-data duplicates for %s"
                   (count id-map) (pr-str datasource-name))
        (doseq [[external-id ids] id-map]
          (db/with-transaction
            (q/modify :article {:article-data-id ids} {:article-data-id (first ids)})
            (q/delete :article-data {:article-data-id (rest ids)})))))))

(defn pubmed-data? [{:keys [datasource-name external-id] :as article-data}]
  (boolean (and (= datasource-name "pubmed")
                (parse-integer external-id))))

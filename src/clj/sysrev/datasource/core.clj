(ns sysrev.datasource.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.shared.util :as sutil]))

(defn get-article-content [external-id]
  (q/find-one :article-data {:external-id external-id} :content))

(defn save-article-data [{:keys [article-type article-subtype title external-id content]
                          :as fields}]
  (q/create :article-data (update fields :content db/to-jsonb)
            :returning :article-data-id))

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

(defn article-data-from-legacy
  [{:keys [article-type article-subtype external-id] :as extra} article]
  {:article-type article-type
   :article-subtype article-subtype
   :title (:primary-title article)
   :external-id (some-> (:public-id article) sutil/parse-integer)
   :content (dissoc article :source-meta :text-search :enabled :article-data-id
                    :article-id :article-uuid :parent-article-uuid :raw)})

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
        (let [article-data-id
              (save-article-data
               {:article-type article-type
                :article-subtype article-subtype
                :title (:primary-title article)
                :external-id nil
                :content (dissoc article :source-meta :text-search :enabled :article-data-id
                                 :article-id :article-uuid :parent-article-uuid :raw)})]
          (q/modify :article {:article-id article-id} {:article-data-id article-data-id})
          nil)))))

;; note: projects #2062 and #3440 have article entries with no article-source somehow
#_ (q/find [:article :a] {} :article-id
           :where (q/not-exists [:article-source :as] {:as.article-id :a.article-id})
           :group-by :project-id)
(defn migrate-article-data []
  (let [article-ids (q/find :article {:article-data-id nil} :article-id)]
    (log/infof "migrating data for %d articles" (count article-ids))
    (->> (map-indexed (fn [i article-id] [i article-id]) article-ids)
         (pmap (fn [[i article-id]]
                 (when (zero? (mod i 1000))
                   (log/infof "copying article #%d...." i))
                 (try (copy-legacy-article-content article-id)
                      (catch Throwable e
                        (log/warnf "got error on article-id=%d" article-id)
                        (log/warn (.getMessage e))))))
         doall)))

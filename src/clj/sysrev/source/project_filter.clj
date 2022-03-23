(ns sysrev.source.project-filter
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
            [honeysql.core :as sql]
            [ring.util.codec :as ring-codec]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.graphql.core :refer [fail]]
            [sysrev.project.article-list :refer [query-project-article-ids]]
            [sysrev.project.core :as project]
            [sysrev.source.core :as source]
            [sysrev.util :as util]
            [sysrev.web.app :as app]))

(def ^:const source-name "Project Filter")

(defn extract-filters-from-url
  "Convert url string `s` into a vector of filters that can be passed to
  `query-project-article-ids`."
  [s]
  (let [{:keys [filters text-search]}
        (some-> s
                (str/split #"\?" 2)
                second
                ring-codec/form-decode
                (#(if (string? %) {} %))
                (walk/keywordize-keys)
                (select-keys [:filters :text-search])
                (update :filters #(when (seq %) (util/read-json %)))
                (util/sanitize-uuids))
        ;; keywords are used as values, but converted to strings in url
        filters (walk/postwalk (fn [x] (if (string? x)
                                         (keyword x)
                                         x)) filters)]
    (vec (concat filters (when text-search [{:text-search text-search}])))))

(defn import-articles [request project-id source-id article-ids]
  (db/with-long-transaction
    [_ (:postgres (:web-server request))]
    (let [old-articles (q/find [:article :a]
                               {:a.article-id article-ids}
                               :*
                               :left-join [[[:article-pdf :pdf] :a.article-id]])
          new-ids (q/create :article
                            (map #(do {:article-data-id (:article-data-id %)
                                       :project-id project-id})
                                 old-articles)
                            :returning :article-id)
          new-pdfs (->> (map
                         (fn [{:keys [s3-id]} article-id]
                           (when s3-id
                             {:article-id article-id :s3-id s3-id}))
                         old-articles
                         new-ids)
                        (filter seq))]
      (when (seq new-pdfs)
        (q/create :article-pdf new-pdfs))
      (q/create :article-source
                (map #(do {:article-id % :source-id source-id}) new-ids)))))

(defn import
  [request project-id {:keys [source-project-id url-filter]}]
  (cond
    (not (project/clone-authorized? source-project-id (app/current-user-id request)))
    {:error {:message "Source project must be public or user must have admin rights to it"}}

    (seq (->> (source/project-sources project-id)
              (filter #(= (get-in % [:meta :url-filter]) url-filter))
              (filter #(= (get-in % [:meta :source-project-id]) source-project-id))))
    {:error {:message (format "%s already imported"
                              (pr-str {:source-project-id source-project-id
                                       :url-filter url-filter}))}}

    :else
    (let [filters (extract-filters-from-url url-filter)
          article-ids (query-project-article-ids {:project-id source-project-id} filters)
          source-meta {:filters filters
                       :importing-articles? true
                       :source source-name
                       :source-project-id source-project-id
                       :url-filter url-filter}
          source-id (q/create
                     :project-source
                     {:import-date (sql/call :now)
                      :meta source-meta
                      :project-id project-id}
                     :returning :source-id)]
      (db/clear-project-cache project-id)
      (future
        (doseq [ids (partition 1000 1000 nil article-ids)]
          (import-articles request project-id source-id ids))
        (source/alter-source-meta source-id #(assoc % :importing-articles? false))
        (db/clear-project-cache project-id))
      {:result true})))

(defn import-article-filter-url!
  [context {url :url source-project-id :sourceID target-project-id :targetID} _]
  (if (= source-project-id target-project-id)
    (fail "source-id can not be the same as target-id")
    (try (import (:request context) target-project-id
                 {:source-project-id source-project-id :url-filter url})
         (resolve-as true)
         (catch Exception e
           (fail (str "There was an exception with message: " (.getMessage e)))))))

(ns sysrev.source.project-filter
  (:refer-clojure :exclude [import])
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
            [honeysql.core :as sql]
            [ring.util.codec :as ring-codec]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.postgres.interface :as pg]
            [sysrev.project.article-list :refer [query-project-article-ids]]
            [sysrev.project.member :as member]
            [sysrev.source.core :as source]
            [sysrev.util :as util]
            [sysrev.web.app :as app]))

(def ^:const source-name "Project Filter")

(defn url-project-id [url]
  (some->> url
           (re-find #".*/p/(\d+)(\/.*)?")
           second
           parse-long))

(defn extract-filters-from-url
  "Convert url string `s` into a vector of filters that can be passed to
  `query-project-article-ids`."
  [s]
  (let [project-id (url-project-id s)
        {:keys [filters text-search]}
        (some-> s
                (str/split #"\?" 2)
                second
                ring-codec/form-decode
                (#(if (string? %) {} %))
                (walk/keywordize-keys)
                (select-keys [:filters :text-search])
                (update :filters #(when (seq %) (util/read-json %)))
                (util/sanitize-uuids))]
    (or (->> (when text-search [{:text-search text-search}])
             (concat filters)
             (map #(assoc % :project-id project-id))
             seq)
        [{:project-id project-id}])))

(defn filters-for-urls [urls]
  (mapcat extract-filters-from-url urls))

(defn get-source-project-articles [source-id article-ids]
  (when (and source-id (seq article-ids))
    (pg/execute!
     (db/connectable)
     {:select [:article-data-id
               [[:'array {:select :s3-id :from :article-pdf :where [:= :article-pdf.article-id :a.article-id]}] :pdf-ids]]
      :from [[:article :a]]
      :where [:and [:in :article-id article-ids]
              [:not
               [:exists
                {:select :*
                 :from [:article]
                 :join [[:article-source :s] [:= :s.article-id :article.article-id]]
                 :where [:and
                         [:= :article.article-data-id :a.article-data-id]
                         [:= :s.source-id source-id]]}]]]})))

(defn import-articles!
  [_sr-context project-id source-id article-ids]
  (let [old-articles (get-source-project-articles source-id article-ids)
        article-data-ids (map :article/article-data-id old-articles)
        dupe-data-ids (set
                       (when (seq article-data-ids)
                         (->> {:select :article-data-id
                               :from :article
                               :where [:and
                                       [:= :project-id project-id]
                                       [:in :article-data-id article-data-ids]]}
                              (pg/execute! (db/connectable))
                              (keep :article/article-data-id))))
        articles-to-import (remove dupe-data-ids article-data-ids)
        new-ids (when (seq article-data-ids)
                  (q/create :article
                            (map #(do {:article-data-id %
                                       :project-id project-id})
                                 articles-to-import)
                            :returning :article-id))
        new-pdfs (mapcat
                  (fn [{:keys [pdf-ids]} article-id]
                    (map #(do {:article-id article-id :s3-id %}) (db/seq-array pdf-ids)))
                  articles-to-import
                  new-ids)]
    (when (seq new-pdfs)
      (q/create :article-pdf new-pdfs))
    (when (seq new-ids)
      (q/create :article-source
                (map #(do {:article-id % :source-id source-id}) new-ids)))))

(defn add-article-data-ids [article-ids]
  (when (seq article-ids)
    (->> {:select [:article-id :article-data-id]
          :from :article
          :where [:in :article-id article-ids]}
         (pg/execute! (db/connectable)))))

(defn match-all-projects [filters]
  (let [articles (->> (group-by :project-id filters)
                      (mapv (fn [[k v]]
                              (add-article-data-ids
                               (query-project-article-ids {:project-id k} v)))))
        article-data-ids (->> articles
                              (map #(set (map :article/article-data-id %)))
                              (apply set/intersection))]
    (doall
     (keep (fn [{:article/keys [article-data-id] :as article}]
             (when (article-data-ids article-data-id)
               article))
           (first articles)))))

(defn count-new-articles-available [{:keys [project-id source-id meta]}]
  (let [article-data-ids (->> meta :url-filters
                              filters-for-urls
                              match-all-projects
                              (map :article/article-data-id))
        current-articles (when (seq article-data-ids)
                           (->> {:select [[:%count.* :ct]]
                                 :from :article
                                 :where [:and
                                         [:= :project-id project-id]
                                         [:in :article-data-id article-data-ids]]}
                                (pg/execute-one! (db/connectable))
                                :ct))]
    (- (count article-data-ids) current-articles)))

(defn import-from-filters!
  [sr-context & {:keys [filters project-id source-id]}]
  (let [article-ids (->> (match-all-projects filters)
                         (map :article/article-id))]
    (doseq [ids (partition 1000 1000 nil article-ids)]
      (import-articles! sr-context project-id source-id ids))
    (source/alter-source-meta source-id #(assoc % :importing-articles? false))
    (db/clear-project-cache project-id)))

(defn create-source! [project-id extra-meta]
  (q/create :project-source
            {:import-date (sql/call :now)
             :meta (assoc extra-meta
                          :importing-articles? true
                          :source source-name)
             :project-id project-id}
            :returning :source-id))

(defn import
  [{:keys [request] :as sr-context} project-id {:keys [url-filters]}]
  (let [user-id (app/current-user-id request)
        filters (filters-for-urls url-filters)]
    (cond
      (->> (map :project-id filters)
           distinct
           (every? #(member/clone-authorized? sr-context % user-id))
           not)
      {:error {:message "Source project must be public or user must have admin rights to it"}}

      :else
      (let [source-id (create-source! project-id {:url-filters url-filters})]
        (db/clear-project-cache project-id)
        (future
          (util/log-errors
           (import-from-filters! sr-context
                                 :filters filters
                                 :project-id project-id
                                 :source-id source-id)))
        {:result {:success true}}))))

(defn import-article-filter-url!
  [context {url :url target-project-id :targetID} _]
  (-> context :request :sr-context
      (import target-project-id {:url-filters [url]}))
  (resolve-as true))

(defmethod source/re-import source-name
  [sr-context project-id {:keys [meta source-id]}]
  (source/alter-source-meta source-id #(assoc % :importing-articles? true))
  (source/set-import-date source-id)
  (import-from-filters! sr-context
                        :filters (filters-for-urls
                                  (or (:url-filters meta)
                                      [(:url-filter meta)]))
                        :project-id project-id
                        :source-id source-id)
  {:source-id source-id})

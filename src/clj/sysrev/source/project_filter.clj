(ns sysrev.source.project-filter
  (:refer-clojure :exclude [import])
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as]]
            [honeysql.core :as sql]
            [ring.util.codec :as ring-codec]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.graphql.core :refer [fail]]
            [sysrev.postgres.interface :as pg]
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

(defn get-source-project-articles [source-id article-ids]
  (pg/execute!
   (db/connectable)
   ;; Work around :array producing square brackets
   {:select [:article-data-id
             [[(keyword "array ") {:select :s3-id :from :article-pdf :where [:= :article-pdf.article-id :a.article-id]}] :pdf-ids]]
    :from [[:article :a]]
    :where [:and [:in :article-id article-ids]
            [:not
             [:exists
              {:select :*
               :from [:article]
               :join [[:article-source :s] [:= :s.article-id :article.article-id]]
               :where [:and
                       [:= :article.article-data-id :a.article-data-id]
                       [:= :s.source-id source-id]]}]]]}))

(defn get-new-articles-available [{:keys [source-id meta]}]
  (let [{:keys [filters source-project-id]} meta]
    (get-source-project-articles
     source-id
     (query-project-article-ids {:project-id source-project-id} filters))))

(defn import-articles!
  [request project-id source-id article-ids & {:keys [ignore-existing?]}]
  (db/with-long-transaction
    [_ (:postgres (:web-server request))]
    (let [old-articles (get-source-project-articles source-id article-ids)
          new-ids (when (seq old-articles)
                    (q/create :article
                              (map #(do {:article-data-id (:article/article-data-id %)
                                         :project-id project-id})
                                   old-articles)
                              :returning :article-id))
          new-pdfs (mapcat
                    (fn [{:keys [pdf-ids]} article-id]
                      (map #(do {:article-id article-id :s3-id %}) (db/seq-array pdf-ids)))
                    old-articles
                    new-ids)]
      (when (seq new-pdfs)
        (q/create :article-pdf new-pdfs))
      (when (seq new-ids)
        (q/create :article-source
                  (map #(do {:article-id % :source-id source-id}) new-ids))))))

(defn import-from-url!
  [request & {:keys [filters project-id source-id source-project-id]}]
  (let [article-ids (query-project-article-ids {:project-id source-project-id} filters)]
    (doseq [ids (partition 1000 1000 nil article-ids)]
      (import-articles! request project-id source-id ids))
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
          source-id (create-source! project-id
                                    {:filters filters
                                     :source-project-id source-project-id
                                     :url-filter url-filter})]
      (db/clear-project-cache project-id)
      (future
        (import-from-url! request
                          :filters filters
                          :project-id project-id
                          :source-id source-id
                          :source-project-id source-project-id))
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

(defmethod source/re-import source-name
  [request project-id {:keys [meta source-id]} _]
  (source/alter-source-meta source-id #(assoc % :importing-articles? true))
  (source/set-import-date source-id)
  (import-from-url! request
                    :filters (-> meta :url-filter extract-filters-from-url)
                    :project-id project-id
                    :source-id source-id
                    :source-project-id (:source-project-id meta))
  {:source-id source-id})

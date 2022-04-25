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
            [sysrev.project.member :as member]
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
                (util/sanitize-uuids))]
    (vec (concat filters (when text-search [{:text-search text-search}])))))

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

(defn get-new-articles-available [{:keys [source-id meta]}]
  (let [{:keys [filters source-project-id]} meta]
    (get-source-project-articles
     source-id
     (query-project-article-ids {:project-id source-project-id} filters))))

(defn import-articles!
  [sr-context project-id source-id article-ids & {:keys [ignore-existing?]}]
  (db/with-long-transaction
    [_ (:postgres sr-context)]
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
  [sr-context & {:keys [filters project-id source-id source-project-id]}]
  (let [article-ids (query-project-article-ids {:project-id source-project-id} filters)]
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
  [{:keys [request] :as sr-context} project-id {:keys [source-project-id url-filter]}]
  (cond
    (not (member/clone-authorized? source-project-id (app/current-user-id request)))
    {:error {:message "Source project must be public or user must have admin rights to it"}}

    (seq (->> (source/project-sources sr-context project-id)
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
        (util/log-errors
         (import-from-url! sr-context
                           :filters filters
                           :project-id project-id
                           :source-id source-id
                           :source-project-id source-project-id)))
      {:result true})))

(defn import-article-filter-url!
  [context {url :url source-project-id :sourceID target-project-id :targetID} _]
  (if (= source-project-id target-project-id)
    (fail "source-id can not be the same as target-id")
    (try (-> context :request :sr-context
             (import target-project-id
                     {:source-project-id source-project-id :url-filter url}))
         (resolve-as true)
         (catch Exception e
           (fail (str "There was an exception with message: " (.getMessage e)))))))

(defmethod source/re-import source-name
  [sr-context project-id {:keys [meta source-id]}]
  (source/alter-source-meta source-id #(assoc % :importing-articles? true))
  (source/set-import-date source-id)
  (import-from-url! sr-context
                    :filters (-> meta :url-filter extract-filters-from-url)
                    :project-id project-id
                    :source-id source-id
                    :source-project-id (:source-project-id meta))
  {:source-id source-id})

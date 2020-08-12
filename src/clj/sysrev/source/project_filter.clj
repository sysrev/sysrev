(ns sysrev.source.project-filter
  (:require [clojure.walk :as walk]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [ring.util.codec :as ring-codec]
            [sysrev.db.queries :as q]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.project.article-list :refer [query-project-article-ids]]
            [sysrev.graphql.core :refer [fail]]
            [sysrev.util :as util]))

(defn extract-filters-from-url
  "Convert url string `s` into a vector of filters that can be passed to
  `query-project-article-ids`."
  [s]
  (let [{:keys [filters text-search]}
        (-> (ring-codec/form-decode s)
            (walk/keywordize-keys)
            (select-keys [:filters :text-search])
            (update-in [:filters] #(json/read-str % :key-fn keyword))
            (util/sanitize-uuids))
        ;; keywords are used as values, but converted to strings in url
        filters (walk/postwalk (fn [x] (if (string? x)
                                         (keyword x)
                                         x)) filters)]
    (vec (concat filters (when text-search [{:text-search text-search}])))))

(defmethod make-source-meta :project-filter
  [_ {:keys [source-project-id url-filter]}]
  {:source "Project Filter"
   :url-filter url-filter :source-project-id source-project-id
   :filters (extract-filters-from-url url-filter)})

(defmethod import-source :project-filter
  [_x project-id {:keys [source-project-id url-filter]} & {:as options}]
  (if (seq (->> (source/project-sources project-id)
                (filter #(= (get-in % [:meta :url-filter]) url-filter))
                (filter #(= (get-in % [:meta :source-project-id]) source-project-id))))
    (do (log/warnf "import-source %s - source exists: %s"
                   _x (pr-str {:source-project-id source-project-id :url-filter url-filter}))
        {:error {:message (format "%s already imported"
                                  (pr-str {:source-project-id source-project-id
                                           :url-filter url-filter}))}})
    (let [filters (extract-filters-from-url url-filter)
          article-ids (query-project-article-ids {:project-id source-project-id} filters)
          entries (q/find [:article :a] {:a.article-id article-ids}
                          [:a.article-id :ad.external-id
                           :ad.title :ad.article-type :ad.article-subtype]
                          :join [[:article-data :ad] :a.article-data-id])
          types (distinct (map #(select-keys % [:datasource-name :article-type :article-subtype])
                               entries))
          articles (mapv (fn [{:keys [external-id title]}]
                           {:primary-title title :external-id external-id})
                         entries)]
      (if (> (count types) 1)
        {:error {:message "Imported articles are not all of same type"}}
        (let [{:keys [article-type article-subtype]} (first types)]
          (import-source-impl
           project-id
           (source/make-source-meta _x {:source-project-id source-project-id
                                        :url-filter url-filter})
           {:types {:article-type article-type :article-subtype article-subtype}
            :get-article-refs (constantly articles)
            :get-articles identity}
           options)
          {:result true})))))

(defn ^ResolverResult import-article-filter-url!
  [_context {url :url source-project-id :sourceID target-project-id :targetID} _]
  (if (= source-project-id target-project-id)
    (fail "source-id can not be the same as target-id")
    (try (import-source :project-filter target-project-id
                        {:source-project-id source-project-id :url-filter url})
         (resolve-as true)
         (catch Throwable e
           (fail (str "There was an exception with message: " (.getMessage e)))))))

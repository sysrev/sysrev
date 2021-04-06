(ns sysrev.source.json
  (:require [sysrev.config :as config]
            [sysrev.formats.json :as json]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.datasource.api :as ds-api]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [sysrev.util :as util :refer [parse-integer]]))

(defn- lookup-filename-sources [project-id filename]
  (->> (source/project-sources project-id)
       (filter #(= filename (get-in % [:meta :filename])))))

(defmethod make-source-meta :json [_ {:keys [filename]}]
  {:source "JSON file" :filename filename})

(defn get-helper-text [{:keys [lvl1 lvl2 lvl3]}]
  (when (or (lvl1 lvl2 lvl3))
    (str
      "| Name | Value | \n"
      "| --- | --- | \n"
      "| lvl1 | " (:lvl1 article) " | \n"
      "| lvl2 | " (:lvl2 article) " | \n"
      "| lvl3 | " (:lvl3 article) " | \n")))

(defmethod import-source :json
  [_ project-id {:keys [file filename]} {:as options}]
  (let [filename-sources (lookup-filename-sources project-id filename)]
    (if (seq filename-sources)
      (do (log/warn "import-source json - non-empty filename-sources -" filename-sources)
          {:error {:message "File name already imported"}})
      (let [data (util/read-json (slurp file))
            articles (->> data
                          :articles
                          (map (fn [article]
                                 [(:ID article) article]))
                          (into {})) 
            source-meta (make-source-meta :json {:filename filename})
            impl {:types {:article-type "file" :article-subtype "json"}
                  :get-article-refs #(->> articles vals (map :ID))
                  :get-articles #(map articles %)
                  ;; :on-article-added #(article-file/save-article-pdf
                  ;;                     (-> (select-keys % [:article-id :filename])
                  ;;                         (assoc :file-bytes (:file-byte-array %))))
                  :prepare-article #(-> (select-keys % [:title :description])
                                        (set/rename-keys {:title :primary-title
                                                          :description :abstract})
                                        (assoc :helper-text (get-helper-text %)))}]
        (import-source-impl project-id source-meta impl options
                            :filename filename :file file)))))

;(import-source :json 102 {:file :filename "ul2.json"} {})

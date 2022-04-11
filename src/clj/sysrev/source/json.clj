(ns sysrev.source.json
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.util :as util]))

(defn- lookup-filename-sources [project-id filename]
  (->> (source/project-sources project-id)
       (filter #(= filename (get-in % [:meta :filename])))))

(defn get-helper-text [{:keys [lvl1 lvl2 lvl3]}]
  (when (or lvl1 lvl2 lvl3)
    (str
     "| Name | Value | \n"
     "| --- | --- | \n"
     "| lvl1 | " lvl1 " | \n"
     "| lvl2 | " lvl2 " | \n"
     "| lvl3 | " lvl3 " | \n")))

(defmethod import-source :json
  [sr-context _ project-id {:keys [file filename]} {:as options}]
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
            source-meta {:source "JSON file" :filename filename}
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
        (import-source-impl sr-context project-id source-meta impl options
                            :filename filename :file file)))))

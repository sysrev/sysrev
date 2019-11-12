(ns sysrev.source.ris
  (:require [clojure.tools.logging :as log]
            [sysrev.datasource.api :as ds-api]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]))

(defmethod make-source-meta :ris [_ {:keys [filename]}]
  {:source "RIS file" :filename filename})

(defn get-title [m]
  (let [{:keys [TI T1]} m]
    (first (if (> (count TI) (count T1)) TI T1))))

(defn ris-get-articles [coll]
  (->> coll
       (map #(let [{:keys [id]} %
                   primary-title (get-title %)]
               {:external-id (str id) :primary-title primary-title}))
       (into [])))

(defmethod import-source :ris [_ project-id {:keys [file filename]} {:as options}]
  (let [source-meta (source/make-source-meta :ris {:filename filename})
        filename-sources (->> (source/project-sources project-id)
                              (filter #(= (get-in % [:meta :filename]) filename)))]
    ;; this source already exists
    (if (seq filename-sources)
      (do (log/warn "import-source RIS - non-empty filename-sources: " filename-sources)
          {:error {:message (str filename " already imported")}})
      ;; attempt to create a RIS citation
      (let [resp (ds-api/create-ris-file {:file file
                                          :filename filename})]
        (if (not= 200 (:status resp))
          {:error {:message (get-in resp [:body :error])}}
          (let [{:keys [hash]} (:body resp)]
            (import-source-impl
             project-id source-meta
             {:types {:article-type "academic" :article-subtype "RIS"}
              :get-article-refs #(ds-api/fetch-ris-articles-by-hash hash)
              :get-articles ris-get-articles}
             options)
            {:result true}))))))

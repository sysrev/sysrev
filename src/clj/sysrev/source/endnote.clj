(ns sysrev.source.endnote
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [sysrev.formats.endnote :refer [endnote-file->articles]]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil]))

(defmethod make-source-meta :endnote-xml [_ {:keys [filename]}]
  {:source "EndNote file" :filename filename})

(defmethod import-source :endnote-xml
  [stype project-id {:keys [file filename]} {:as options}]
  (let [source-meta (source/make-source-meta :endnote-xml {:filename filename})
        project-sources (source/project-sources project-id)
        filename-sources (->> project-sources
                              (filter #(= (get-in % [:meta :filename]) filename)))]
    (cond
      (not-empty filename-sources)
      (do (log/warn "import-source endnote-xml - non-empty filename-sources - "
                    filename-sources)
          {:error {:message "File name already imported"}})

      :else
      (import-source-impl
       project-id source-meta
       {:get-article-refs #(-> file io/reader endnote-file->articles doall)
        :get-articles identity}
       options
       :filename filename :file file))))

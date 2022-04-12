(ns sysrev.source.endnote
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [sysrev.formats.endnote :refer [endnote-file->articles]]
            [sysrev.source.core :as source]
            [sysrev.source.interface :refer [import-source import-source-impl]]))

(defmethod import-source :endnote-xml
  [sr-context _ project-id {:keys [file filename]} {:as options}]
  (let [filename-sources (->> (source/project-sources sr-context project-id)
                              (filter #(= (get-in % [:meta :filename]) filename)))]
    (if (seq filename-sources)
      (do (log/warn "import-source endnote-xml - non-empty filename-sources:" filename-sources)
          {:error {:message "File name already imported"}})
      (import-source-impl
       sr-context project-id
       {:source "EndNote file" :filename filename}
       {:types {:article-type "academic" :article-subtype "endnote"}
        :get-article-refs #(-> file io/reader endnote-file->articles doall)
        :get-articles identity}
       options
       :filename filename :file file))))

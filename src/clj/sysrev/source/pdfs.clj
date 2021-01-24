(ns sysrev.source.pdfs
  (:require [clojure.set :as set]
            [me.raynes.fs :as fs]
            [sysrev.file.article :as article-file]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]
            [sysrev.util :as util]))

(defmethod make-source-meta :pdfs [_ {:keys [filenames]}]
  {:source "PDF Files" :filenames filenames})

(defmethod import-source :pdfs
  [_ project-id files {:as options}]
  (let [filenames (map :filename files)
        source-meta (make-source-meta :pdfs {:filenames filenames})
        pdf-to-article (fn [{:keys [filename tempfile] :as _entry}]
                         {:filename (fs/base-name filename)
                          :file-byte-array (util/slurp-bytes tempfile)})
        impl {:types {:article-type "file" :article-subtype "pdf"}
              :get-article-refs (constantly files)
              :get-articles #(map pdf-to-article %)
              :on-article-added #(article-file/save-article-pdf
                                  (-> (select-keys % [:article-id :filename])
                                      (assoc :file-bytes (:file-byte-array %))))
              :prepare-article #(-> (set/rename-keys % {:filename :primary-title})
                                    (dissoc :file-byte-array))}]
    (import-source-impl project-id source-meta impl options)))

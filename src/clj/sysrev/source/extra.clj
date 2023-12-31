(ns sysrev.source.extra
  (:require [sysrev.source.interface :refer [import-source import-source-impl]]))

(defmethod import-source :api-text-manual
  [sr-context _ project-id {:keys [articles]} {:as options}]
  (import-source-impl
   sr-context project-id
   {:source "API Text Manual" :article-count (count articles)}
   {:types {:article-type "text" :article-subtype "generic"}
    :get-article-refs (constantly articles)
    :get-articles identity}
   options))

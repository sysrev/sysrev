(ns sysrev.source.extra
  (:require [sysrev.source.interface :refer [import-source import-source-impl]]))

(defmethod import-source :api-text-manual
  [request _ project-id {:keys [articles]} {:as options}]
  (import-source-impl
   request project-id
   {:source "API Text Manual" :article-count (count articles)}
   {:types {:article-type "text" :article-subtype "generic"}
    :get-article-refs (constantly articles)
    :get-articles identity}
   options))

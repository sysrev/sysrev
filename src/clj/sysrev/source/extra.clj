(ns sysrev.source.extra
  (:require [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]))

(defmethod make-source-meta :api-text-manual [_ {:keys [article-count]}]
  {:source "API Text Manual" :article-count article-count})

(defmethod make-source-meta :legacy [_ {:keys []}]
  {:source "legacy"})

(defmethod make-source-meta :custom [_ {:keys [description]}]
  {:source "Custom import" :custom description})

(defmethod import-source :api-text-manual
  [request _ project-id {:keys [articles]} {:as options}]
  (import-source-impl
   request project-id
   (make-source-meta :api-text-manual {:article-count (count articles)})
   {:types {:article-type "text" :article-subtype "generic"}
    :get-article-refs (constantly articles)
    :get-articles identity}
   options))

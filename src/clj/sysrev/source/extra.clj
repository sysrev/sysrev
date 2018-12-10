(ns sysrev.source.extra
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sysrev.source.core :as source :refer [make-source-meta]]
            [sysrev.source.interface :refer [import-source import-source-impl]]))

(defmethod make-source-meta :api-text-manual [_ {:keys [article-count]}]
  {:source "API Text Manual" :article-count article-count})

(defmethod make-source-meta :legacy [_ {:keys []}]
  {:source "legacy"})

(defmethod make-source-meta :custom [_ {:keys [description]}]
  {:source "Custom import" :custom description})

(defmethod import-source :api-text-manual
  [stype project-id {:keys [articles]} {:as options}]
  (import-source-impl
   project-id
   (make-source-meta :api-text-manual {:article-count (count articles)})
   {:get-article-refs (constantly articles), :get-articles identity}
   options))

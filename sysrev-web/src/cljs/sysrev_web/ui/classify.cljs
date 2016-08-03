(ns sysrev-web.ui.classify
  (:require [sysrev-web.base :refer [label-queue label-queue-head debug-box]]
            [sysrev-web.routes :refer [label-queue-update]]
            [sysrev-web.ui.home :refer [similarity-card]]))

(defn classify []
  (fn []
    (label-queue-update)
    (let [adata (label-queue-head)
          article (:article adata)
          score (- 1.0 (Math/abs (:score adata)))
          percent (Math/round (* 100 score))
          aid (:articleId adata)]
      [:div.ui.container
       [similarity-card article nil score percent aid]])))

(ns sysrev-web.ui.user
  (:require [sysrev-web.base :refer [state server-data debug-box]]
            [sysrev-web.ajax :refer [get-article
                                     get-ranking-article-ids
                                     get-ui-filtered-article-ids
                                     get-classified-ids]]
            [cljs.pprint :as pprint :refer [cl-format]]
            [sysrev-web.ui.base :refer [out-link]]
            [sysrev-web.react.components :refer [link]]
            [sysrev-web.routes :as routes]
            [reagent.core :as r]))

(defn user []
  (fn []
    [:div
     [:div "USER !"]
     [link routes/home "Go to /"]]))

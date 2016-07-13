(ns sysrev-web.ajax
    (:require-macros [cljs.core.async.macros :refer [go]])
    (:require [reagent.core :as r]
              [ajax.core :as ajax]
              [cljs-http.client :as http]
              [cljs.core.async :refer [<!]]
              [pushy.core :as pushy]
              [sysrev-web.base :refer [state state-set]]))

(defn ajax-get [url handler]
  (ajax/GET url
            :format :json
            :response-format :json
            :keywords? true
            :handler handler))


(defn pull-initial-data []
  (ajax-get "/api/ranking"
            (fn [response] (swap! state assoc :page 0 :articles (:result response)))))

(defn get-ranking-page [num]
  (ajax-get (str "/api/ranking/" num) (fn [response] (swap! state assoc-in :page num :articles (:result response)))))

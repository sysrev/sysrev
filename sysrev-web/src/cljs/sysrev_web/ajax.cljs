(ns sysrev-web.ajax
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [reagent.core :as r]
            [ajax.core :as ajax]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [pushy.core :as pushy]
            [sysrev-web.base :refer [state state-set server-data]]))

(defn ajax-get [url handler]
  (ajax/GET url
            :format :json
            :response-format :json
            :keywords? true
            :handler handler))

(defonce page-data-fields
  {:home
   [[:criteria] [:ranking]]})

(defn data-initialized? [page]
  (let [required-fields (get page-data-fields page)]
    (every? #(not= (get-in @server-data % :not-found)
                   :not-found)
            required-fields)))

(defn pull-criteria []
  (when (nil? (:criteria @server-data))
    (ajax-get "/api/criteria"
              (fn [response]
                (swap! server-data assoc :criteria (:result response))))))

(defn pull-ranking-page [num]
  (when (nil? (get-in @server-data [:ranking :pages num]))
    (ajax-get (str "/api/ranking/" num)
              (fn [response]
                (swap! server-data assoc-in [:ranking :pages num] (:result response))))))

(defn pull-initial-data []
  (pull-criteria)
  (let [rpage (:ranking-page @state)]
    (when rpage (pull-ranking-page rpage))))

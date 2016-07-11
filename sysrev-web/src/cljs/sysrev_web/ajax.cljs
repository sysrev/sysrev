(ns sysrev-web.ajax
    (:require-macros [cljs.core.async.macros :refer [go]])
    (:require [reagent.core :as r]
              [ajax.core :as ajax]
              [cljs-http.client :as http]
              [cljs.core.async :refer [<!]]
              [pushy.core :as pushy]))

(defn ajax-get [url handler]
  (ajax/GET url
            :format :json
            :response-format :json
            :keywords? true
            :handler handler))

(defn pull-initial-data []
  #_
  (ajax-get "/some-api-call" (fn [response] (do-something response)))
  nil)

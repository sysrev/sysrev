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

(defn ajax-post [url content handler]
  (ajax/POST url
             :format :json
             :response-format :json
             :keywords? true
             :params content
             :handler handler))

(defonce page-data-fields
  {:home
   [[:criteria] [:ranking] [:articles] [:articles-criteria]]})

(defn data-initialized? [page]
  (let [required-fields (get page-data-fields page)]
    (every? #(not= (get-in @server-data % :not-found)
                   :not-found)
            required-fields)))

(defn mapify-id-t [result]
  (let [ids (mapv :id result)
        entries (->> result
                     (map #(vector (:id %) (:t %)))
                     (apply concat)
                     (apply hash-map))]
    {:ids ids :entries entries}))

(defn pull-criteria []
  (when (nil? (:criteria @server-data))
    (ajax-get "/api/criteria"
              (fn [response]
                (swap! server-data assoc
                       :criteria (mapify-id-t (:result response)))))))

(defn pull-articles-criteria []
  (ajax-get "/api/allcriteria"
            (fn [response]
              (swap! server-data assoc
                     :articles-criteria (:result response)))))

(defn pull-ranking-page [num]
  (when (nil? (get-in @server-data [:ranking :pages num]))
    (ajax-get (str "/api/ranking/" num)
              (fn [response]
                (let [mapified (mapify-id-t (:result response))]
                  (swap! server-data
                         #(assoc % :articles
                                 (merge (:articles %) (:entries mapified))))
                  (swap! server-data assoc-in
                         [:ranking :pages num] (:ids mapified)))))))

(defn pull-initial-data []
  (pull-criteria)
  (pull-articles-criteria)
  (let [rpage (:ranking-page @state)]
    (when rpage (pull-ranking-page rpage))))

(defn get-ranking-article-ids [page-num]
  (get-in @server-data [:ranking :pages page-num]))

(defn get-article-criteria [id]
  (let [id-keyword (keyword (str id))]
    (->> (get-in @server-data [:articles-criteria id-keyword])
         (sort-by :id))))

(defn get-article [id]
  (let [article (get-in @server-data [:articles id])]
    (when article
      (assoc article :criteria (get-article-criteria id)))))

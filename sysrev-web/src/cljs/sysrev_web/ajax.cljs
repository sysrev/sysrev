(ns sysrev-web.ajax
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [reagent.core :as r]
            [ajax.core :as ajax]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [pushy.core :as pushy]
            [sysrev-web.base :refer [state state-set server-data debug]]
            [clojure.set :refer [intersection]]))

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

(defn handle-result [f] #(f (:result %)))

(defn request-articles-criteria [f]
  (ajax-get "/api/allcriteria"
    (handle-result f)))

(defonce page-data-fields
  {:home
   [[:criteria] [:ranking] [:articles] [:articles-criteria]]})

(defn data-initialized? [page]
  (let [required-fields (get page-data-fields page)]
    (every? #(not= :not-found (get-in @server-data % :not-found))
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
              (let [result (:result response)
                    {articles :articles criteria :criteria} result]
                (swap! server-data assoc
                       :articles-criteria criteria)
                (swap! server-data update :articles #(merge % articles))))))

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

(defn takeOnly [els]
  (if debug (take 10 els) els))

(defn get-ranking-article-ids [page-num]
  (takeOnly (get-in @server-data [:ranking :pages page-num])))

(defn get-ui-filtered-article-ids []
  (let [af (:article-filter @state)
        in-cids (set (map #(-> % first name int) (filter val af)))
        ex-cids (map first (filter #(-> % val false?) af))
        acs (:articles-criteria @server-data)
        ;get the list of criteria ids in common with a given article's criteria.
        has-criteria (fn [criteria] (intersection (set (map :id criteria)) in-cids))
        meets-criteria (fn [criteria])
        f-acs (filter (fn [[aid criteria]] (has-criteria criteria)) acs)]
    (keys f-acs)))

(defn get-classified-ids []
  (let [af (:articles-criteria @server-data)]
    (map #(-> % name int) (keys af))))

(defn insist-key [s]
  (if (keyword? s) s (keyword (str s))))

(defn get-article-criteria [id]
  (->> (get-in @server-data [:articles-criteria (insist-key id)])
       (sort-by :id)))

(defn get-article [id]
  (let [article (get-in @server-data [:articles (insist-key id)])]
    (when article
      (assoc article :criteria (get-article-criteria id)))))

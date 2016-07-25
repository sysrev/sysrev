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

(defn pull-criteria []
  (when (nil? (:criteria @server-data))
    (ajax-get "/api/criteria"
              (fn [response]
                (swap! server-data assoc
                       :criteria (:result response))))))

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
                (let [mapified (:result response)]
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


(defn critbyId [criteria id]
  (println criteria)
  (println id)
  (let [rcrit (first (filter #(= id (:id %)) criteria))
        res (when-not (empty? rcrit) (:answer rcrit))]
    (println rcrit)
    (println res)
    res))


(defn get-ui-filtered-article-ids []
  (let [af (:article-filter @state)
        articles (:articles @server-data)
        res-keys
        ;; here we get a list of keys based on the ui filters.
        ;; If no filter chosen, just get all the article keys.
          (if (empty? af)
            (keys (:articles @server-data))
            (let [in-cids (set (keys (filter val af)))
                  ex-cids (map first (filter #(-> % val false?) af))
                  acs (:articles-criteria @server-data)
                  meets-criteria (fn [criteria] (every? #(critbyId criteria (int (name %))) in-cids))
                  filter-fn (fn [[_ criteria]] (meets-criteria criteria))]
              (keys (filter filter-fn acs))))]
       ; Sort list of keys by their ranking score.
       (sort-by #(get-in articles [% :score]) res-keys)))

(defn get-classified-ids []
  (let [af (:articles-criteria @server-data)]
    (map #(-> % name int) (keys af))))

(defn get-article-criteria [id]
  (->> (get-in @server-data [:articles-criteria id])
       (sort-by :id)))

(defn get-article [id]
  (let [article (get-in @server-data [:articles id])]
    (when article
      (assoc article :criteria (get-article-criteria id)))))

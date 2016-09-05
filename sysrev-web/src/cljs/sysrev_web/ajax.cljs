(ns sysrev-web.ajax
  (:require
   [ajax.core :refer [GET POST]]
   [sysrev-web.base :refer [state server-data]]
   [sysrev-web.util :refer [nav nav-scroll-top mapify-by-id]]
   [sysrev-web.notify :refer [notify]]))

(defn integerify-map-keys
  "Maps parsed from JSON with integer keys will have the integers changed 
  to keywords. This converts any integer keywords back to integers, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-int (js/parseInt (name k))
                       k-new (if (integer? k-int) k-int k)
                       ;; integerify sub-maps recursively
                       v-new (if (map? v)
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn ajax-get
  "Performs an AJAX GET call with proper JSON handling.
  Calls function `handler` on the response map."
  ([url content handler]
   (GET url
        :format :json
        :response-format :json
        :keywords? true
        :params content
        :handler #(handler (integerify-map-keys %))))
  ([url handler]
   (ajax-get url nil handler)))

(defn ajax-post
  "Performs an AJAX POST call with proper JSON handling.
  Calls function `handler` on the response map."
  ([url content handler]
   (POST url
         :format :json
         :response-format :json
         :keywords? true
         :params content
         :handler #(handler (integerify-map-keys %))))
  ([url handler]
   (ajax-post url nil handler)))

(def get-identity (partial ajax-get "/api/auth/identity"))
(def get-all-labels (partial ajax-get "/api/all-labels"))
(def get-criteria (partial ajax-get "/api/criteria"))
(defn get-article-labels [article-id handler]
  (ajax-get (str "/api/article-labels/" article-id) handler))
(defn get-ranking-page [num handler]
  (ajax-get (str "/api/ranking" num) handler))
(def get-project-info (partial ajax-get "/api/project-info"))
(defn post-login [data handler] (ajax-post "/api/auth/login" data handler))
(defn post-register [data handler] (ajax-post "/api/auth/register" data handler))
(defn post-logout [handler] (ajax-post "/api/auth/logout" handler))
(defn post-submit-tag [data handler] (ajax-post "/api/tag" data handler))
(defn get-label-tasks
  ([interval above-score handler]
   (ajax-get
     (str "/api/label-task/" interval)
     (when-not (nil? above-score) {:above-score above-score})
     handler))
  ([interval handler] (get-label-tasks interval nil handler)))
(defn post-tags [data handler] (ajax-post "/api/set-labels" data handler))


(defn pull-identity []
  (get-identity #(swap! state assoc :identity (:identity %))))

(defn pull-criteria []
  (when (nil? (:criteria @server-data))
    (get-criteria
     #(swap! server-data assoc :criteria %))))

(defn pull-all-labels []
  (get-all-labels
   (fn [response]
     (swap! server-data assoc
            :labels (:labels response)
            :labeled-articles (:articles response))
     (swap! server-data update
            :articles #(merge % (:articles response))))))

(defn pull-article-labels [article-id & [handler]]
  (get-article-labels
    article-id
    (fn [response]
      (swap! server-data assoc-in
             [:article-labels article-id] response)
      (when handler
        (handler response)))))

(defn pull-ranking-page [num]
  (when (nil? (get-in @server-data [:ranking :pages num]))
    (get-ranking-page
      num
      (fn [response]
        (let [ranked-ids (->> response
                              (sort-by (comp :score second))
                              (mapv first))]
          (swap! server-data update
                 :articles #(merge % response))
          (swap! server-data assoc-in
                 [:ranking :pages num] ranked-ids))))))

(defn pull-project-info []
  (get-project-info #(swap! server-data assoc :sysrev %)))

(defn pull-initial-data []
  (pull-identity)
  (when (nil? (:criteria @server-data)) (pull-criteria))
  (when (nil? (:labels @server-data)) (pull-all-labels))
  (when (nil? (:sysrev @server-data)) (pull-project-info))
  (when (contains? (:page @state) :ranking)
    (let [page-num (-> @state :page :ranking :ranking-page)]
      (when page-num
        (pull-ranking-page page-num)))))

(defn do-post-login [email password]
  (post-login
    {:email email :password password}
    (fn [response]
      (if (:valid response)
        (do
          (pull-identity)
          (nav-scroll-top "/"))
        (swap! state assoc-in [:page :login :err] (:err response))))))

(defn do-post-register [email password]
  (post-register
   {:email email :password password}
   ;; if register succeeds, send login request
   (fn [_] (do-post-login email password))))

(defn do-post-logout []
  (post-logout
   (fn [_]
     (swap! state dissoc :identity)
     (nav-scroll-top "/")
     (notify "Logged out."))))

(defn submit-tag [{:keys [article-id criteria-id value]}]
  (post-submit-tag
   {:article-id article-id
    :criteria-id criteria-id
    :value value}
   (fn [_] (notify "Tag saved"))))

(defn pull-label-tasks
  ([interval handler above-score]
   (get-label-tasks
     interval
     above-score
     (fn [response]
       (when-let [result (:result response)]
         (let [article-ids (map :article_id result)
               articles (mapify-by-id :article_id false result)]
           (swap! server-data update
                  :articles #(merge % articles))
           (notify (str "Fetched " (count result) " more articles"))
           (handler article-ids))))))
  ([interval handler]
   (pull-label-tasks interval handler 0.0)))

(defn send-tags
  "Update the database with user label values for `article-id`.
  `criteria-values` is a map of criteria-ids to booleans.
  Any unset criteria-ids will be unset on the server.
  Will fail on server if user is not logged in."
  [article-id criteria-values]
  (post-tags
    {:article-id article-id
     :label-values criteria-values}
    (fn [response]
      (let [err (:error response)
            res (:result response)]
        (when-not (empty? err) (notify (str "Error: " err)))
        (when-not (empty? res) (notify "Tags saved"))))))

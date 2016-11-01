(ns sysrev-web.ajax
  (:require
   [ajax.core :refer [GET POST]]
   [sysrev-web.base :refer [state]]
   [sysrev-web.state.core :as s]
   [sysrev-web.state.data :as d :refer [data]]
   [sysrev-web.util :refer [nav scroll-top nav-scroll-top map-values]]
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
(def get-criteria (partial ajax-get "/api/criteria"))
(defn get-article-info [article-id handler]
  (ajax-get (str "/api/article-info/" article-id) handler))
(def get-article-documents (partial ajax-get "/api/article-documents"))
(defn get-ranking-page [num handler]
  (ajax-get (str "/api/ranking" num) handler))
(def get-project-info (partial ajax-get "/api/project-info"))
(def get-all-projects (partial ajax-get "/api/all-projects"))
(defn get-user-info [user-id handler]
  (ajax-get (str "/api/user-info/" user-id) handler))
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
(defn post-set-labels [data handler] (ajax-post "/api/set-labels" data handler))
(defn post-confirm-labels [data handler] (ajax-post "/api/confirm-labels" data handler))

(defn pull-user-info [user-id]
  (get-user-info
   user-id
   (fn [response]
     (let [new-articles
           (->> (:articles response)
                vec
                (filter
                 (fn [[article-id article]]
                   (nil? (data [:articles article-id :abstract]))))
                (apply concat)
                (apply hash-map))]
       (swap! state (d/merge-articles new-articles))
       (swap! state (d/set-user-info user-id response))))))

(defn pull-identity []
  (get-identity
   (fn [response]
     (swap! state (s/set-identity (:identity response)))
     (when-let [user-id (s/current-user-id)]
       (when-not (d/user-info user-id)
         (pull-user-info user-id))))))

(defn pull-criteria []
  (when (nil? (d/data :criteria))
    (get-criteria
     (fn [response]
       (swap! state (d/set-criteria response))))))

(defn pull-article-info [article-id]
  (get-article-info
   article-id
   (fn [response]
     (swap! state (d/set-article-labels article-id (:labels response)))
     (swap! state (d/merge-articles {article-id (:article response)})))))

(defn pull-article-documents []
  (get-article-documents
   (fn [response]
     (swap! state (d/merge-documents response)))))

(defn pull-ranking-page [num]
  (when (nil? (d/data [:ranking :pages num]))
    (get-ranking-page
     num
     (fn [response]
       (let [ranked-ids (->> response
                             (sort-by (comp :score second))
                             (mapv first))]
         (swap! state
                (comp (d/set-ranking-page num ranked-ids)
                      (d/merge-articles response))))))))

(defn pull-project-info []
  (get-project-info
   #(swap! state (d/set-project-info %))))

(defn pull-all-projects []
  (get-all-projects
   #(swap! state (d/set-all-projects %))))

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
     (swap! state (s/log-out))
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
              articles (->> result
                            (group-by :article_id)
                            (map-values first))]
          (swap! state (d/merge-articles articles))
          ;; (notify (str "Fetched " (count result) " more articles"))
          (handler result))))))
  ([interval handler]
   (pull-label-tasks interval handler 0.0)))

(defn fetch-classify-task [& [force?]]
  (let [current-id (data :classify-article-id)]
    (when (or force? (nil? current-id))
      (let [current-score
            (if (nil? current-id)
              nil
              (data [:articles current-id :score]))]
        (pull-label-tasks
         1
         #(swap! state (s/set-classify-task
                        (-> % first :article_id)
                        (-> % first :review-status)))
         current-score)))))

(defn confirm-labels [article-id label-values on-confirm]
  (post-confirm-labels
   {:article-id article-id
    :label-values label-values}
   (fn [response]
     (let [err (:error response)
           res (:result response)]
       (when-not (empty? err) (notify (str "Error: " err)))
       (when-not (empty? res)
         (notify "Labels submitted")
         (pull-article-info article-id)
         (pull-user-info (s/current-user-id))
         (when (= article-id (data :classify-article-id))
           (fetch-classify-task true))
         (on-confirm))))))

(defn send-labels
  "Update the database with user label values for `article-id`.
  `criteria-values` is a map of criteria-ids to booleans.
  Any unset criteria-ids will be unset on the server.
  Will fail on server if user is not logged in.
  If `confirm?` is true, will also mark these labels as confirmed."
  [article-id criteria-values]
  (post-set-labels
   {:article-id article-id
    :label-values criteria-values}
   (fn [response]
     (let [err (:error response)
           res (:result response)]
       (when-not (empty? err) (notify (str "Error: " err)))
       (when-not (empty? res) (notify "Labels saved"))))))

(defn fetch-data
  "Fetches the data value under path `ks` in (:data @state) if it does
  not already exist (or if `force?` is true)."
  [ks & [force?]]
  (let [ks (if (keyword? ks) [ks] ks)]
    (when (or force? (nil? (d/data ks)))
      (case (first ks)
        :criteria (pull-criteria)
        :sysrev (pull-project-info)
        :all-projects (pull-all-projects)
        :users (let [[_ user-id] ks] (pull-user-info user-id))
        :ranking (let [[_ pages page-num] ks]
                   (when (and (= pages :pages)
                              (integer? page-num))
                     (pull-ranking-page page-num)))
        :articles (let [[_ article-id] ks]
                    ;; todo - fetch single articles here
                    (pull-article-info article-id))
        :article-labels (let [[_ article-id] ks]
                          (pull-article-info article-id))
        :classify-article-id (fetch-classify-task force?)
        :documents (pull-article-documents)
        nil))))

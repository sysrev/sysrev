(ns sysrev-web.ajax
  (:require
   [ajax.core :refer [GET POST]]
   [sysrev-web.base :refer [state ga ga-event]]
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

(defn request-error [url message data]
  (ga-event "error" "ajax_error" url)
  (notify message {:class "red" :display-ms 2000})
  (println (str url " - " message))
  (println (pr-str data)))

(defn ajax-get
  "Performs an AJAX GET call with proper JSON handling.
  Calls function `handler` on the response map."
  ([url content handler]
   (GET url
        :format :json
        :response-format :json
        :keywords? true
        :headers (when-let [csrf-token (s/csrf-token)]
                   {"x-csrf-token" csrf-token})
        :params content
        :handler #(do
                    (when (contains? % :csrf-token)
                      (swap! state (s/set-csrf-token (:csrf-token %))))
                    (if (contains? % :result)
                      (-> % :result integerify-map-keys handler)
                      (request-error url "Server error (empty result from request)" %)))
        :error-handler #(request-error url "Server error (request failed)" %)))
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
         :headers (when-let [csrf-token (s/csrf-token)]
                    {"x-csrf-token" csrf-token})
         :params content
         :handler #(do
                     (when (contains? % :csrf-token)
                       (swap! state (s/set-csrf-token (:csrf-token %))))
                     (if (contains? % :result)
                       (-> % :result integerify-map-keys handler)
                       (request-error url "Server error (action had empty result)" %)))
         :error-handler #(request-error url "Server error (action failed)" %)))
  ([url handler]
   (ajax-post url nil handler)))

(def get-identity (partial ajax-get "/api/auth/identity"))
(def get-criteria (partial ajax-get "/api/criteria"))
(defn get-article-info [article-id handler]
  (ajax-get (str "/api/article-info/" article-id) handler))
(def get-article-documents (partial ajax-get "/api/article-documents"))
(def get-project-info (partial ajax-get "/api/project-info"))
(def get-all-projects (partial ajax-get "/api/all-projects"))
(defn get-user-info [user-id handler]
  (ajax-get (str "/api/user-info/" user-id) handler))
(defn post-login [data handler] (ajax-post "/api/auth/login" data handler))
(defn post-register [data handler] (ajax-post "/api/auth/register" data handler))
(defn post-logout [handler] (ajax-post "/api/auth/logout" handler))
(defn get-label-task [handler]
  (ajax-get "/api/label-task" handler))
(defn post-set-labels [data handler] (ajax-post "/api/set-labels" data handler))
(defn post-select-project [project-id handler]
  (ajax-post "/api/select-project"
             {:project-id project-id}
             handler))

(defn select-project [project-id]
  (post-select-project
   project-id
   (fn [result]
     (notify "Project selected")
     (swap! state (s/change-project project-id))
     (nav-scroll-top "/"))))

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
     (swap! state (s/set-active-project-id (:active-project response)))
     (when-let [user-id (s/current-user-id)]
       (when-not (d/user-info user-id)
         (pull-user-info user-id)))
     (when-let [project-id (s/active-project-id)]
       (pull-project-info)))))

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
         (ga-event "auth" "login_success")
         (notify "Logged in" {:class "green"})
         (pull-identity)
         (nav-scroll-top "/"))
       (do
         (ga-event "auth" "login_failure")
         (swap! state assoc-in [:page :login :err] (:message response)))))))

(defn do-post-register [email password]
  (post-register
   {:email email :password password}
   ;; if register succeeds, send login request
   (fn [response]
     (if (:success response)
       (do (ga-event "auth" "register_success")
           (notify "Account created" {:class "green"})
           (do-post-login email password))
       (do (ga-event "auth" "register_failure")
           (swap! state assoc-in [:page :register :err] (:message response)))))))

(defn do-post-logout []
  (post-logout
   (fn [response]
     (if (:success response)
       (ga-event "auth" "logout_success")
       (ga-event "auth" "logout_failure"))
     (swap! state (s/log-out))
     (nav-scroll-top "/")
     (notify "Logged out"))))

(defn fetch-classify-task [& [force?]]
  (let [current-id (data :classify-article-id)]
    (when (or force? (nil? current-id))
      (let [current-score
            (if (nil? current-id)
              nil
              (data [:articles current-id :score]))]
        (get-label-task
         (fn [result]
           (swap! state (d/merge-article result))
           (swap! state (s/set-classify-task
                         (:article-id result)
                         (:review-status result)))
           (notify "Fetched next article")))))))

(defn send-labels
  "Update the database with user label values for `article-id`.
  `criteria-values` is a map of criteria-ids to booleans.
  Any unset criteria-ids will be unset on the server.
  Will fail on server if user is not logged in."
  [article-id label-values]
  (post-set-labels
   {:article-id article-id
    :label-values label-values
    :confirm false}
   (fn [result]
     (notify "Labels saved" {:display-ms 800}))))

(defn confirm-labels
  "Same as `send-labels` but also marks the labels as confirmed."
  [article-id label-values on-confirm]
  (post-set-labels
   {:article-id article-id
    :label-values label-values
    :confirm true}
   (fn [result]
     (ga-event "labels" "confirm_success"
               (str "article-id = " article-id))
     (notify "Labels submitted" {:class "green"})
     (pull-article-info article-id)
     (pull-user-info (s/current-user-id))
     (when (= article-id (data :classify-article-id))
       (fetch-classify-task true))
     (on-confirm))))

(defn fetch-data
  "Fetches the data value under path `ks` in (:data @state) if it does
  not already exist (or if `force?` is true)."
  [ks & [force?]]
  (let [ks (if (keyword? ks) [ks] ks)]
    (when (or force? (nil? (d/data ks)))
      (case (first ks)
        :sysrev (pull-project-info)
        :all-projects (pull-all-projects)
        :users (let [[_ user-id] ks] (pull-user-info user-id))
        :articles (let [[_ article-id] ks]
                    ;; todo - fetch single articles here
                    (pull-article-info article-id))
        :article-labels (let [[_ article-id] ks]
                          (pull-article-info article-id))
        :classify-article-id (fetch-classify-task force?)
        :documents (pull-article-documents)
        nil))))

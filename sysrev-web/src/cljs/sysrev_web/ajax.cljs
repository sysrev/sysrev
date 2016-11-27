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
(defn get-reset-code-info [reset-code handler]
  (ajax-get "/api/auth/lookup-reset-code"
            {:reset-code reset-code}
            handler))
(def get-article-documents (partial ajax-get "/api/article-documents"))
(def get-project-info (partial ajax-get "/api/project-info"))
(def get-all-projects (partial ajax-get "/api/all-projects"))
#_ (defn get-user-info [user-id handler]
     (ajax-get (str "/api/user-info/" user-id) handler))
(defn get-member-labels [user-id handler]
  (ajax-get (str "/api/member-labels/" user-id) handler))
(defn post-login [data handler] (ajax-post "/api/auth/login" data handler))
(defn post-register [data handler] (ajax-post "/api/auth/register" data handler))
(defn post-reset-password [data handler] (ajax-post "/api/auth/reset-password" data handler))
(defn post-request-password-reset [data handler]
  (ajax-post "/api/auth/request-password-reset" data handler))
(defn post-logout [handler] (ajax-post "/api/auth/logout" handler))
(defn get-label-task [handler]
  (ajax-get "/api/label-task" handler))
(defn post-set-labels [data handler] (ajax-post "/api/set-labels" data handler))
(defn post-select-project [project-id handler]
  (ajax-post "/api/select-project"
             {:project-id project-id}
             handler))
(defn post-join-project [project-id handler]
  (ajax-post "/api/join-project"
             {:project-id project-id}
             handler))
(defn post-delete-user [handler]
  (ajax-post "/api/delete-user"
             {:verify-user-id (s/current-user-id)}
             handler))
(defn post-delete-member-labels [handler]
  (ajax-post "/api/delete-member-labels"
             {:verify-user-id (s/current-user-id)}
             handler))



(defn pull-member-labels [user-id]
  (get-member-labels
   user-id
   (fn [result]
     (->>
      (comp
       (d/merge-articles (:articles result))
       (d/set-member-labels user-id (:labels result)))
      (swap! state)))))

(defn pull-identity []
  (get-identity
   (fn [response]
     (swap! state (s/set-identity (:identity response)))
     (swap! state (s/set-active-project-id (:active-project response))))))

(defn pull-article-info [article-id]
  (get-article-info
   article-id
   (fn [response]
     (swap! state (d/set-article-labels article-id (:labels response)))
     (swap! state (d/merge-articles {article-id (:article response)})))))

(defn pull-reset-code-info [reset-code]
  (get-reset-code-info
   reset-code
   (fn [response]
     (swap! state (d/set-reset-code-info reset-code response)))))

(defn pull-article-documents []
  (get-article-documents
   (fn [response]
     (swap! state (d/merge-documents response)))))

(defn pull-project-info []
  (get-project-info
   (fn [result]
     (->>
      (comp (d/set-project-info (:project result))
            (d/merge-users (:users result)))
      (swap! state)))))

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

(defn do-post-reset-password [reset-code password]
  (post-reset-password
   {:reset-code reset-code :password password}
   (fn [response]
     (if (:success response)
       (do (ga-event "auth" "password_reset_success")
           (notify "Password reset" {:class "green"})
           (swap! state (s/log-out))
           (nav-scroll-top "/login"))
       (do (ga-event "error" "password_reset_failure")
           (swap! state assoc-in [:page :reset-password :err]
                  (or (:message response) "Request failed")))))))

(defn do-post-request-password-reset [email]
  (post-request-password-reset
   {:email email}
   (fn [response]
     (if (:success response)
       (swap! state assoc-in
              [:page :request-password-reset :sent] true)
       (do
         (swap! state assoc-in
                [:page :request-password-reset :sent] false)
         (swap! state assoc-in [:page :request-password-reset :err]
                "No account found for this email address."))))))

(defn do-post-logout []
  (post-logout
   (fn [response]
     (if (:success response)
       (ga-event "auth" "logout_success")
       (ga-event "auth" "logout_failure"))
     (swap! state (s/log-out))
     (nav-scroll-top "/")
     (notify "Logged out"))))

(defn do-post-delete-user []
  (post-delete-user
   (fn [result]
     (swap! state (s/log-out))
     (nav-scroll-top "/")
     (notify "Account deleted")
     (notify "Logged out"))))

(defn do-post-delete-member-labels []
  (post-delete-member-labels
   (fn [result]
     (swap! state assoc :data {}())
     (nav-scroll-top "/")
     (notify "Labels deleted"))))

(defn select-project [project-id]
  (post-select-project
   project-id
   (fn [result]
     (notify "Project selected")
     (swap! state (s/change-project project-id))
     (nav-scroll-top "/"))))

(defn join-project [project-id]
  (post-join-project
   project-id
   (fn [result]
     (notify "Joined project" {:class "green" :display-ms 2000})
     (swap! state (s/change-project project-id))
     (pull-identity)
     (nav-scroll-top "/"))))

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
     (pull-member-labels (s/current-user-id))
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
        :project (cond
                   (<= (count ks) 2)
                   (pull-project-info)
                   (and (= (nth ks 2) :labels)
                        (integer? (nth ks 3)))
                   (pull-member-labels (nth ks 3)))
        :reset-code (let [[_ reset-code] ks]
                      (pull-reset-code-info reset-code))
        :all-projects (pull-all-projects)
        ;; :users (let [[_ user-id] ks] (pull-user-info user-id))
        :articles (let [[_ article-id] ks]
                    ;; todo - fetch single articles here
                    (pull-article-info article-id))
        :article-labels (let [[_ article-id] ks]
                          (pull-article-info article-id))
        :classify-article-id (fetch-classify-task force?)
        :documents (pull-article-documents)
        nil))))

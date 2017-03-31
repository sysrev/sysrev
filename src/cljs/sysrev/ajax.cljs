(ns sysrev.ajax
  (:require [ajax.core :refer [GET POST]]
            [sysrev.base :refer [st work-state ga ga-event force-display-ready]]
            [sysrev.state.core :as st :refer [data]]
            [sysrev.state.project :as project]
            [sysrev.state.labels :as l]
            [sysrev.state.notes :as notes]
            [sysrev.shared.util :as us :refer [map-values]]
            [sysrev.util :refer
             [nav nav-scroll-top dissoc-in]]
            [sysrev.notify :refer [notify]])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(declare join-project)

(defn integerify-map-keys
  "Maps parsed from JSON with integer keys will have the integers changed 
  to keywords. This converts any integer keywords back to integers, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-int (and (re-matches #"^\d+$" (name k))
                                  (js/parseInt (name k)))
                       k-new (if (integer? k-int) k-int k)
                       ;; integerify sub-maps recursively
                       v-new (if (map? v)
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn uuidify-map-keys
  "Maps parsed from JSON with UUID keys will have the UUID string values changed
  to keywords. This converts any UUID keywords to string values, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-uuid
                       (and (keyword? k)
                            (re-matches
                             #"^[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+$"
                             (name k))
                            (name k))
                       k-new (if (string? k-uuid) k-uuid k)
                       ;; uuidify sub-maps recursively
                       v-new (if (map? v)
                               (uuidify-map-keys v)
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
   (using-work-state
    (GET url
         :format :json
         :response-format :json
         :keywords? true
         :headers (when-let [csrf-token (st/csrf-token)]
                    {"x-csrf-token" csrf-token})
         :params content
         :handler
         #(using-work-state
           (when (contains? % :csrf-token)
             (swap! work-state (st/set-csrf-token (:csrf-token %))))
           (if (contains? % :result)
             (-> % :result integerify-map-keys uuidify-map-keys handler)
             (request-error url "Server error (empty result from request)" %)))
         :error-handler
         #(using-work-state
           (request-error url "Server error (request failed)" %)))))
  ([url handler]
   (ajax-get url nil handler)))

(defn ajax-post
  "Performs an AJAX POST call with proper JSON handling.
  Calls function `handler` on the response map."
  ([url content handler]
   (using-work-state
    (POST url
          :format :json
          :response-format :json
          :keywords? true
          :headers (when-let [csrf-token (st/csrf-token)]
                     {"x-csrf-token" csrf-token})
          :params content
          :handler
          #(using-work-state
            (when (contains? % :csrf-token)
              (swap! work-state (st/set-csrf-token (:csrf-token %))))
            (if (contains? % :result)
              (-> % :result integerify-map-keys uuidify-map-keys handler)
              (request-error url "Server error (action had empty result)" %)))
          :error-handler
          #(using-work-state
            (request-error url "Server error (action failed)" %)))))
  ([url handler]
   (ajax-post url nil handler)))

(defn pull-member-labels [user-id]
  (ajax-get
   (str "/api/member-labels/" user-id)
   (fn [result]
     (->>
      (comp
       (project/merge-articles (:articles result))
       (l/set-member-labels user-id (:labels result))
       (notes/ensure-user-note-fields user-id (:notes result)))
      (swap! work-state)))))

(defn pull-identity []
  (ajax-get
   "/api/auth/identity"
   (fn [response]
     (->>
      (comp
       (st/set-identity (:identity response))
       (st/set-current-project-id (:active-project response)))
      (swap! work-state))
     (when (:active-project response)
       (pull-member-labels (st/current-user-id))))))

(defn pull-article-info [article-id]
  (ajax-get
   (str "/api/article-info/" article-id)
   (fn [response]
     (->>
      (comp
       (l/set-article-labels article-id (:labels response))
       (project/merge-articles {article-id (:article response)})
       (notes/ensure-article-note-fields article-id (:notes response)))
      (swap! work-state)))))

(defn pull-reset-code-info [reset-code]
  (ajax-get
   "/api/auth/lookup-reset-code"
   {:reset-code reset-code}
   (fn [response]
     (swap! work-state (st/set-reset-code-info reset-code response)))))

(defn pull-article-documents []
  (ajax-get
   "/api/article-documents"
   (fn [response]
     (swap! work-state (project/merge-documents response)))))

(defn pull-project-info []
  (ajax-get
   "/api/project-info"
   (fn [result]
     (->>
      (comp (project/set-project-info (:project result))
            (st/merge-users (:users result)))
      (swap! work-state)))))

(defn pull-all-projects []
  (ajax-get
   "/api/all-projects"
   #(swap! work-state (st/set-all-projects %))))

(defn post-login [email password]
  (ajax-post
   "/api/auth/login"
   {:email email :password password}
   (fn [response]
     (if (:valid response)
       (do
         (ga-event "auth" "login_success")
         #_ (notify "Logged in" {:class "green"})
         ;; use dissoc to emulate pull-identity having not run yet
         (swap! work-state dissoc :identity)
         (nav-scroll-top "/")
         (pull-identity))
       (do
         (ga-event "auth" "login_failure")
         (swap! work-state assoc-in [:page :login :err] (:message response)))))))

(defn post-register [email password & [join-project-id]]
  (ajax-post
   "/api/auth/register"
   {:email email :password password :project-id join-project-id}
   ;; if register succeeds, send login request
   (fn [response]
     (if (:success response)
       (do (ga-event "auth" "register_success")
           #_ (notify "Account created" {:class "green"})
           (post-login email password))
       (do (ga-event "auth" "register_failure")
           (swap! work-state assoc-in [:page :register :err] (:message response)))))))

(defn post-reset-password [reset-code password]
  (ajax-post
   "/api/auth/reset-password"
   {:reset-code reset-code :password password}
   (fn [response]
     (if (:success response)
       (do (ga-event "auth" "password_reset_success")
           #_ (notify "Password reset" {:class "green"})
           (swap! work-state (st/log-out))
           (nav-scroll-top "/login"))
       (do (ga-event "error" "password_reset_failure")
           (swap! work-state assoc-in [:page :reset-password :err]
                  (or (:message response) "Request failed")))))))

(defn post-request-password-reset [email]
  (ajax-post
   "/api/auth/request-password-reset"
   {:email email}
   (fn [response]
     (if (:success response)
       (swap! work-state assoc-in
              [:page :request-password-reset :sent] true)
       (do
         (swap! work-state assoc-in
                [:page :request-password-reset :sent] false)
         (swap! work-state assoc-in [:page :request-password-reset :err]
                "No account found for this email address."))))))

(defn post-logout []
  (ajax-post
   "/api/auth/logout"
   (fn [response]
     (if (:success response)
       (ga-event "auth" "logout_success")
       (ga-event "auth" "logout_failure"))
     (swap! work-state (st/log-out))
     (nav-scroll-top "/")
     #_ (notify "Logged out"))))

(defn post-delete-user []
  (using-work-state
   (ajax-post
    "/api/delete-user"
    {:verify-user-id (st/current-user-id)}
    (fn [result]
      (swap! work-state (st/log-out))
      (nav-scroll-top "/")
      (notify "Account deleted")
      #_ (notify "Logged out")))))

(defn post-delete-member-labels []
  (using-work-state
   (ajax-post
    "/api/delete-member-labels"
    {:verify-user-id (st/current-user-id)}
    (fn [result]
      (swap! work-state assoc :data {}())
      (nav-scroll-top "/")
      (notify "Labels deleted")))))

(defn select-project [project-id]
  (ajax-post
   "/api/select-project"
   {:project-id project-id}
   (fn [result]
     #_ (notify "Project selected")
     (swap! work-state (st/change-project project-id))
     (nav-scroll-top "/"))))

(defn join-project [project-id]
  (ajax-post
   "/api/join-project"
   {:project-id project-id}
   (fn [result]
     #_ (notify "Joined project" {:class "green" :display-ms 2000})
     (swap! work-state (st/change-project project-id))
     (pull-identity)
     (nav-scroll-top "/"))))

(defn fetch-classify-task [& [force? on-success]]
  (using-work-state
   (let [current-id (data :classify-article-id)]
     (when (or force? (nil? current-id))
       (when force?
         (swap! work-state dissoc-in [:data :classify-article-id]))
       (force-display-ready false)
       (ajax-get
        "/api/label-task"
        (fn [result]
          (->>
           (comp
            (project/merge-article result)
            (st/set-classify-task (:article-id result)
                                  (:review-status result)))
           (swap! work-state))
          (when on-success
            (on-success))
          #_ (notify "Fetched next article")))))))

(defn send-labels
  "Update the database with user label values for `article-id`."
  [article-id label-values]
  (ajax-post
   "/api/set-labels"
   {:article-id article-id
    :label-values label-values
    :confirm false}
   (fn [result]
     #_ (notify "Labels saved" {:display-ms 800})
     nil)))

(defn confirm-labels
  "Same as `send-labels` but also marks the labels as confirmed."
  [article-id label-values & [on-confirm on-fetch]]
  (ajax-post
   "/api/set-labels"
   {:article-id article-id
    :label-values label-values
    :confirm true}
   (fn [result]
     (ga-event "labels" "confirm_success"
               (str "article-id = " article-id))
     (when (= article-id (data :classify-article-id))
       (fetch-classify-task true on-fetch))
     #_ (notify "Labels submitted" {:class "green"})
     (pull-article-info article-id)
     (pull-member-labels (st/current-user-id))
     (when on-confirm
       (on-confirm)))))

(defn send-active-labels
  "Calls `send-labels` with values from the active editor."
  []
  (using-work-state
   (when-let [article-id (l/active-editor-article-id)]
     (let [active-values
           (->> (l/active-label-values)
                (l/filter-valid-label-values))]
       (swap! work-state
              assoc-in [:page (st/current-page) :label-values-sent article-id]
              active-values)
       (send-labels article-id active-values)))))

(defn confirm-active-labels
  "Calls `confirm-labels` with values from the active editor."
  [& [on-confirm on-fetch]]
  (using-work-state
   (when (l/editing-article-labels?)
     (confirm-labels (l/active-editor-article-id)
                     (l/active-label-values)
                     on-confirm
                     on-fetch))))

(defn send-article-note
  [article-id note-name content]
  (ajax-post
   "/api/set-article-note"
   {:article-id article-id
    :name note-name
    :content content}
   (fn [result]
     (swap! work-state (notes/update-note-saved
                        article-id note-name content)))))

(defn fetch-data
  "Fetches the data value under path `ks` in (:data @work-state) if it does
  not already exist (or if `force?` is true)."
  [ks & [force?]]
  (using-work-state
   (let [ks (if (keyword? ks) [ks] ks)]
     (when (or force? (= :not-found (data ks :not-found)))
       (case (first ks)
         :project (cond
                    (<= (count ks) 2)
                    (pull-project-info)
                    (and (= (nth ks 2) :member-labels)
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
         nil)))))


(def send-file-url "/api/files/upload")

(defn pull-files []
  (let [project-id (st/current-project-id)]
    (ajax-get
      "/api/files"
      (fn [result]
        (swap! work-state assoc-in [:data :project project-id :files] result)))))

(defn delete-file [key]
  (ajax-post
    (str "/api/files/delete/" key)
    pull-files))

(defn get-file-url [key name]
  (str "api/files/" key "/" name))

(defn get-file [key name]
  (-> js/window
      (aset "location" (get-file-url key name))))
(ns sysrev.action.definitions
  (:require
   [re-frame.core :as re-frame :refer
    [dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.action.core :refer [def-action]]
   [sysrev.subs.auth :refer [current-user-id]]
   [sysrev.subs.project :refer [active-project-id]]
   [sysrev.subs.review :as review]
   [sysrev.util :refer [dissoc-in]]))

(def-action :auth/log-in
  :uri (fn [_ _] "/api/auth/login")
  :content (fn [email password]
             {:email email :password password})
  :process
  (fn [_ _ {:keys [valid message] :as result}]
    (if valid
      {:dispatch-n
       (list [:ga-event "auth" "login_success"]
             [:self/unset-identity]
             [:do-login-redirect]
             #_ [:fetch [:identity]])
       ;; :reset-data true
       :reload-page [true 50]}
      {:dispatch-n
       (list [:ga-event "auth" "login_failure"]
             [:set-login-error-msg message])})))

(def-action :auth/log-out
  :uri (fn [] "/api/auth/logout")
  :process
  (fn [{:keys [db]} _ result]
    {:db (-> db
             (assoc-in [:state :identity] nil)
             (assoc-in [:state :active-project-id] nil)
             (dissoc-in [:state :self]))
     :reset-data true
     :nav-scroll-top "/"
     :dispatch [:fetch [:identity]]}))

(def-action :auth/register
  :uri (fn [& _] "/api/auth/register")
  :content (fn [email password & [project-id]]
             {:email email :password password :project-id project-id})
  :process
  (fn [_ [email password] {:keys [success message] :as result}]
    (if success
      {:dispatch-n
       (list [:ga-event "auth" "register_success"]
             [:action [:auth/log-in email password]])}
      {:dispatch-n
       (list [:ga-event "auth" "register_failure"]
             [:set-login-error-msg message])})))

(def-action :auth/request-password-reset
  :uri (fn [_] "/api/auth/request-password-reset")
  :content (fn [email] {:email email})
  :process
  (fn [_ [email] {:keys [success] :as result}]
    (if success
      {:dispatch [:request-password-reset/sent? true]}
      {:dispatch-n
       (list [:request-password-reset/sent? false]
             [:request-password-reset/error
              "No account found for this email address."])})))

(def-action :auth/reset-password
  :uri (fn [_] "/api/auth/reset-password")
  :content (fn [{:keys [reset-code password] :as args}]
             args)
  :process
  (fn [_ _ {:keys [success message] :as result}]
    (if success
      {:dispatch-n
       (list [:ga-event "auth" "password_reset_success"]
             [:reset-password/success? true])
       :dispatch-later
       [{:ms 2000 :dispatch [:navigate [:login]]}]}
      {:dispatch-n
       (list [:ga-event "error" "password_reset_failure"]
             [:reset-password/error
              (or message "Request failed")])})))

(def-action :dev/clear-query-cache
  :uri (fn [] "/api/clear-query-cache")
  :process
  (fn [_ _ result]
    {:reset-data true}))

(def-action :select-project
  :uri (fn [_] "/api/select-project")
  :content (fn [id] {:project-id id})
  :process
  (fn [_ [id] result]
    {:dispatch [:self/set-active-project id]
     :nav-scroll-top "/"}))

(def-action :join-project
  :uri (fn [_] "/api/join-project")
  :content (fn [id] {:project-id id})
  :process
  (fn [_ [id] result]
    {:dispatch-n
     (list [:fetch [:identity]]
           [:self/set-active-project id])
     :nav-scroll-top "/"}))

(def-action :review/send-labels
  :uri (fn [_] "/api/set-labels")
  :content (fn [{:keys [article-id label-values change? confirm? resolve?]}]
             {:article-id article-id
              :label-values label-values
              :confirm? (boolean confirm?)
              :resolve? (boolean resolve?)
              :change? (boolean change?)})
  :process
  (fn [{:keys [db]} [{:keys [on-success]}] result]
    (when on-success
      (let [success-fns (filter fn? on-success)
            success-events (remove fn? on-success)]
        (doseq [f success-fns] (f))
        {:dispatch-n success-events}))))

(def-action :article/send-note
  :uri (fn [_] "/api/set-article-note")
  :content (fn [{:keys [article-id name content] :as argmap}]
             argmap)
  (fn [{:keys [db]} {:keys [article-id name content]} result]
    (when-let [user-id (current-user-id db)]
      {:dispatch-n (list [:article/set-note-content
                          article-id (keyword name) content]
                         [:review/set-note-content
                          article-id (keyword name) nil])})))

(def-action :project/change-settings
  :uri (fn [_] "/api/change-project-settings")
  :content (fn [changes] {:changes changes})
  :process
  (fn [{:keys [db]} _ {:keys [settings]}]
    (let [project-id (active-project-id db)]
      {:dispatch [:project/load-settings project-id settings]})))

(def-action :user/change-settings
  :uri (fn [_] "/api/change-user-settings")
  :content (fn [changes] {:changes changes})
  :process
  (fn [{:keys [db]} _ {:keys [settings]}]
    {:dispatch [:self/load-settings settings]}))

(def-action :user/delete-member-labels
  :uri (fn [_] "/api/delete-member-labels")
  :content (fn [verify-user-id]
             {:verify-user-id verify-user-id})
  :process
  (fn [_ _ result]
    {:reset-data true}))

(def-action :user/delete-account
  :uri (fn [_] "/api/delete-user")
  :content (fn [verify-user-id]
             {:verify-user-id verify-user-id})
  :process
  (fn [{:keys [db]} _ result]
    {:db (-> db
             (assoc-in [:state :identity] nil)
             (assoc-in [:state :active-project-id] nil)
             (dissoc-in [:state :self]))
     :reset-data true
     :nav-scroll-top "/"
     :dispatch [:fetch [:identity]]}))

(def-action :files/delete-file
  :uri (fn [file-id] (str "/api/files/delete/" file-id))
  :process
  (fn [_ _ result]
    {:dispatch [:reload [:project/files]]}))

(def-action :create-project
  :uri (fn [_] "/api/create-project")
  :content (fn [project-name]
             {:project-name project-name})
  :process
  (fn [_ _ {:keys [success message project] :as result}]
    ;; send out for the new project to update
    ;; identity
    (if success
      {:dispatch-n
       (list [:fetch [:identity]]
             [:action [:select-project (:project-id project)]])}
      ;; does nothing, code needs to be written
      {:dispatch-n
       (list [:set-create-project-error-msg message])})))

;; stub out
#_(def-action :project/import-articles-from-search
  :uri (fn [] "/api/import-articles-from-search")
  :content (fn [search-term source]
             {:search-term search-term
              :source source})
  :process
  (fn [_ _ {:keys [success] :as result}]
    (if success
      ;;
      {:dispatch-n
       (list
        ;; send out event to check for article sources
        ;; status
        [:fetch [:project/project-sources]]
        ;; clear state of pubmed.cljs
        )}
      ;; does nothing, code should be created
      {:dispatch-n
       ;;       (list [:set-import-articles-error-msg message])
       (list)
       })))

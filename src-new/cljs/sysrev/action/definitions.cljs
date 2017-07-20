(ns sysrev.action.definitions
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.action.core :refer [def-action]]
   [sysrev.util :refer [dissoc-in]]))

(def-action :log-in
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
             [:fetch [:identity]])
       :reset-data true}
      {:dispatch-n
       (list [:ga-event "auth" "login_failure"]
             [:set-login-error-msg message])})))

(def-action :log-out
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

(def-action :register-user
  :uri (fn [& _] "/api/auth/register")
  :content (fn [email password & [project-id]]
             {:email email :password password :project-id project-id})
  :process
  (fn [_ [email password] [{:keys [success message] :as result}]]
    (if success
      {:dispatch-n
       (list [:ga-event "auth" "register_success"]
             [:action [:log-in email password]])}
      {:dispatch-n
       (list [:ga-event "auth" "register_failure"]
             [:set-login-error-msg message])})))

(def-action :clear-query-cache
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

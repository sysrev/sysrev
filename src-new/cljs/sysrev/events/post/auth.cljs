(ns sysrev.events.post.auth
  (:require
   [re-frame.core :as re-frame :refer
    [reg-event-db reg-event-fx subscribe dispatch dispatch-sync trim-v]]
   [day8.re-frame.http-fx]
   [sysrev.base :refer [ga-event]]
   [sysrev.shared.util :refer [in? to-uuid]]
   [sysrev.events.ajax :refer [reg-event-ajax reg-event-ajax-fx run-ajax]]
   [sysrev.routes :refer [nav-scroll-top]]))

(reg-event-fx
 :log-out
 (fn [db]
   (run-ajax
    {:method :post
     :uri "/api/auth/logout"
     :on-success [::process-log-out]})))

(reg-event-ajax-fx
 ::process-log-out
 (fn [{:keys [db]} [result]]
   {:db (-> db
            (assoc-in [:state :identity] nil)
            (assoc-in [:state :active-project-id] nil)
            (assoc :data {}))
    :nav-scroll-top "/"
    :dispatch [:fetch [:identity]]}))

(reg-event-fx
 :log-in
 [trim-v]
 (fn [_ [email password]]
   (run-ajax
    {:method :post
     :uri "/api/auth/login"
     :content {:email email :password password}
     :on-success [::process-log-in]})))

(reg-event-ajax-fx
 ::process-log-in
 (fn [_ [{:keys [valid message] :as result}]]
   (if valid
     {:dispatch-n
      (list [:ga-event "auth" "login_success"]
            [:unset-identity]
            [:do-login-redirect]
            [:fetch [:identity]])}
     {:dispatch-n
      (list [:ga-event "auth" "login_failure"]
            [:set-login-error-msg message])})))

(reg-event-fx
 :register-user
 [trim-v]
 (fn [_ [email password & [project-id]]]
   (run-ajax
    {:method :post
     :uri "/api/auth/register"
     :content {:email email :password password :project-id project-id}
     :on-success [::process-register-user]
     :action-params [email password]})))

(reg-event-ajax-fx
 ::process-register-user
 (fn [_ [[email password]
         [{:keys [success message] :as result}]]]
   (if success
     {:dispatch-n
      (list [:ga-event "auth" "register_success"]
            [:log-in email password])}
     {:dispatch-n
      (list [:ga-event "auth" "register_failure"]
            [:set-login-error-msg message])})))

(ns sysrev.views.panels.user.verify-email
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.nav :as nav]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.views.semantic :refer [Message]]
            [sysrev.shared.util :as sutil :refer [parse-integer]]
            [sysrev.macros :refer-macros [setup-panel-state sr-defroute with-loader]]))

;; for clj-kondo
(declare panel panel-get panel-set)

(setup-panel-state panel [:user :verify-email] {:get-fn panel-get :set-fn panel-set
                                                :get-sub ::get :set-event ::set})

(defn- do-verify-redirect [user-id]
  (-> #(nav/nav-scroll-top (str "/user/" user-id "/email"))
      (js/setTimeout 1000)))

(def-action :user/verify-email
  :method :put
  :uri (fn [user-id code] (str "/api/user/" user-id "/email/verify/" code))
  :process (fn [{:keys [db]} [user-id] _]
             (do-verify-redirect user-id)
             {:db (-> (panel-set db :verify-message "Thank you for verifying your email address.")
                      (panel-set :verify-error nil))})
  :on-error (fn [{:keys [db error]} [user-id] _]
              (do-verify-redirect user-id)
              {:db (-> (panel-set db :verify-message nil)
                       (panel-set :verify-error (or (:message error)
                                                    "An error occurred while verifying email.")))}))

(defn VerifyEmail []
  ;; wait for [:identity] to avoid anti-forgery token error
  (with-loader [[:identity]] {}
    (let [{:keys [code verify-message verify-error]} @(subscribe [::get])
          user-id @(subscribe [:user-panel/user-id])]
      (when (and user-id code (nil? verify-message) (nil? verify-error))
        (dispatch [:action [:user/verify-email user-id code]]))
      [:div
       (when-not (str/blank? verify-message)
         [Message verify-message])
       (when-not (str/blank? verify-error)
         [Message {:negative true} verify-error])
       [:div {:style {:margin-top "1em"}}
        "Redirecting to email settings..."]])))

(defmethod panel-content panel []
  (fn [_child] [VerifyEmail]))

(sr-defroute user-email-verify "/user/:user-id/email/:code" [user-id code]
             (let [user-id (parse-integer user-id)]
               (dispatch [:user-panel/set-user-id user-id])
               (dispatch [::set [] {:code code}])
               (dispatch [:set-active-panel panel])))

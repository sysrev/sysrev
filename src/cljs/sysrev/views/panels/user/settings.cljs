(ns sysrev.views.panels.user.settings
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.action.core :as action :refer [def-action]]
            [sysrev.views.semantic :as S :refer
             [Segment Header Grid Column Radio Message]]
            [sysrev.views.components.core :as ui]
            [sysrev.util :as util :refer [parse-integer format]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel with-loader]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:user :settings]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(defn- UserDevTools []
  (let [user-id @(subscribe [:self/user-id])]
    (when @(subscribe [:user/dev?])
      [:div.ui.segment
       [:h4.ui.dividing.header "Dev Tools"]
       [:div
        ;; TODO: add method for deleting dev user labels
        #_[:button.ui.yellow.button
           {:on-click
            #(do (dispatch [:action [:user/delete-member-labels user-id]])
                 (nav/nav "/"))}
           "Delete Member Labels"]
        [:button.ui.orange.button
         {:on-click #(dispatch [:action [:user/delete-account user-id]])}
         "Delete Account"]]])))

(def-data :user/public-reviewer
  :uri      (fn [user-id] (format "/api/user/%d/groups/public-reviewer/active" user-id))
  :loaded?  (fn [db user-id] (-> (get-in db [:data :user-public-reviewer])
                                 (contains? user-id)))
  :process  (fn [{:keys [db]} [user-id] {:keys [enabled]}]
              {:db (assoc-in db [:data :user-public-reviewer user-id] (boolean enabled))}))

(reg-sub :user/public-reviewer
         (fn [db [_ user-id]]
           (get-in db [:data :user-public-reviewer user-id])))

(def-action :user/set-public-reviewer
  :method   :put
  :uri      (fn [user-id _] (format "/api/user/%d/groups/public-reviewer/active" user-id))
  :content  (fn [_ enabled] {:enabled enabled})
  :process  (fn [_ [user-id _] _] {:dispatch [:data/load [:user/public-reviewer user-id]]}))

;; this feature isn't currently being used
#_{:clj-kondo/ignore [:unused-private-var]}
(defn- PublicReviewerOptIn []
  (when-let [user-id @(subscribe [:self/user-id])]
    (with-loader [[:user/public-reviewer user-id]] {}
      (let [enabled @(subscribe [:user/public-reviewer user-id])
            verified @(subscribe [:self/verified])
            loading? (or (data/loading? :user/public-reviewer)
                         (action/running? :user/set-public-reviewer))]
        [Segment
         [Header {:as "h4" :dividing true} "Public Reviewer Opt In"]
         (when-not verified
           [Message {:warning true}
            [:a {:href (str "/user/" user-id "/email")}
             "Your email address is not yet verified."]])
         [Radio {:toggle true
                 :id "opt-in-public-reviewer"
                 :label "Publicly Listed as a Paid Reviewer"
                 :checked enabled
                 :disabled (or (not verified) loading?)
                 :on-click #(dispatch [:action [:user/set-public-reviewer
                                                user-id (not enabled)]])}]]))))

(def-action :user/developer-enable
  :method   :put
  :uri      (fn [user-id _] (format "/api/user/%d/developer/enable" user-id))
  :content  (fn [_ enabled] {:enabled? enabled})
  :process  (fn [_ _ _]
              {:dispatch-n [[:fetch [:identity]]
                            [::set [:developer-enable-error] nil]]})
  :on-error (fn [{:keys [error]} _ _]
              {:dispatch-n [[:fetch [:identity]]
                            [::set [:developer-enable-error] (:message error)]]}))

(defn EnableDevAccount []
  (let [user-id @(subscribe [:self/user-id])
        email @(subscribe [:self/email])
        enabled @(subscribe [:user/dev-account-enabled?])
        error-message (r/cursor state [:developer-enable-error])]
    [Segment
     [Header {:as "h4" :dividing true} "Enable Developer Account"]
     (when enabled
       [:p "API Key: " [:b#user-api-key @(subscribe [:user/api-key])]])
     (when enabled
       [:p "You can login at "
        [:a {:href "https://datasource.insilica.co" :target "_blank"} "datasource.insilica.co"]
        " using " [:b email] " and your SysRev password."])
     (when-not enabled
       [:p "Create your own custom datasources and import them using the SysRev and Datasource GraphQL interface."])
     (when-not enabled
       [:p "A developer account allows full access to SysRev and Datasource, the underlying data backend."])
     [:p "In addition to the SysRev and Datasource GraphQL interface, we provide an "
      [:a {:href "https://github.com/sysrev/RSysrev" :target "_blank"} "R library"] "."]
     [Radio {:toggle true
             :id "enable-dev-account"
             :label "Developer Account"
             :checked enabled
             :on-click #(dispatch [:action [:user/developer-enable user-id (not enabled)]])}]
     [ui/CursorMessage error-message {:negative true}]]))

(defn- UserSettings [{:keys [user-id]}]
  [Grid {:class "user-settings" :stackable true :columns 2}
   [Column
    #_[PublicReviewerOptIn]
    [EnableDevAccount]]
   [Column [UserDevTools]]])

(def-panel :uri "/user/:user-id/settings" :params [user-id] :panel panel
  :on-route (let [user-id (parse-integer user-id)]
              (dispatch [:user-panel/set-user-id user-id])
              (dispatch [:reload [:identity]])
              (dispatch [:set-active-panel panel]))
  :content [UserSettings]
  :require-login true)

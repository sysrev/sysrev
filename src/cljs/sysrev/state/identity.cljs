(ns sysrev.state.identity
  (:require [re-frame.core :refer [reg-sub reg-event-db trim-v]]
            [sysrev.state.core :refer [store-user-map]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.util :as util :refer [in? to-uuid dissoc-in]]))

(defn have-identity? [db]
  (contains? (:state db) :identity))
(reg-sub :have-identity? have-identity?)

(def-data :identity
  :loaded? have-identity?
  :prereqs (constantly nil)
  :uri (fn [] "/api/auth/identity")
  :process
  (fn [{:keys [db]} _ {:keys [identity projects orgs]}]
    (let [have-user? (contains? identity :user-id)
          cur-theme (util/ui-theme-from-dom-css)
          new-theme (or (get-in identity [:settings :ui-theme]) "Default")
          theme-changed? (and cur-theme (not= cur-theme new-theme))
          identity (some-> identity (update :user-uuid to-uuid))
          url-ids-map (->> projects
                           (map (fn [{:keys [project-id url-ids]}]
                                  (map (fn [u] [u project-id])
                                       url-ids)))
                           (apply concat)
                           (apply concat)
                           (apply hash-map))]
      (cond->
          {:db (cond-> (-> db
                           (assoc-in [:state :identity] identity)
                           (assoc-in [:state :self :projects] projects)
                           (assoc-in [:state :self :orgs] orgs))
                 have-user? (store-user-map identity))
           :dispatch-n (list [:load-project-url-ids url-ids-map])}
        theme-changed? (merge {:reload-page [true]})))))

(def-action :auth/log-in
  :uri (fn [_ _ _] "/api/auth/login")
  :content (fn [email password _redirect]
             {:email email :password password})
  :process
  (fn [_ [_ _ redirect] {:keys [valid message]}]
    (if valid
      {:dispatch-n
       (list [:ga-event "auth" "login_success"]
             [:set-login-redirect-url redirect]
             [:do-login-redirect])}
      {:dispatch-n
       (list [:ga-event "auth" "login_failure"]
             [:set-login-error-msg message])})))

(def-action :auth/log-out
  :uri (fn [] "/api/auth/logout")
  :process (fn [{:keys [db]} _ _]
             {:reset-data true :nav ["/"]}))

(def-action :auth/register
  :uri (fn [& _] "/api/auth/register")
  :content (fn [email password & [project-id _redirect]]
             {:email email :password password :project-id project-id})
  :process
  (fn [_ [email password & [_ redirect]] {:keys [success message]}]
    (if success
      {:dispatch-n
       (list [:ga-event "auth" "register_success"]
             [:action [:auth/log-in email password redirect]])}
      {:dispatch-n
       (list [:ga-event "auth" "register_failure"]
             [:set-login-error-msg message])})))

(reg-sub ::identity #(get-in % [:state :identity]))

(reg-sub :self/email
         :<- [::identity]
         #(:email %))

(reg-sub :self/verified
         :<- [::identity]
         #(:verified %))

(defn current-user-id [db]
  (get-in db [:state :identity :user-id]))

(reg-sub :self/user-id current-user-id)

(reg-sub :self/logged-in?
         :<- [:self/user-id]
         #(some? %))

(reg-sub ::self-state #(get-in % [:state :self]))

(defn get-self-projects [db & {:keys [include-available?]}]
  (let [{:keys [projects]} (get-in db [:state :self])]
    (if include-available?
      projects
      (->> projects (filterv :member?)))))

(reg-sub :self/projects
         :<- [::self-state]
         (fn [{:keys [projects]} [_ include-available?]]
           (if include-available?
             projects
             (filterv :member? projects))))

(reg-sub      :self/orgs
              :<- [::self-state]
              #(:orgs %))
(reg-event-db :self/orgs [trim-v]
              (fn [db [orgs]] (assoc-in db [:state :self :orgs] orgs)))

(reg-sub :self/org-permissions
         :<- [:self/orgs]
         (fn [orgs [_ org-id]]
           ((comp :permissions first)
            (filter #(= (:group-id %) org-id) orgs))))

(reg-sub :self/member?
         :<- [:self/user-id]
         :<- [:self/projects false]
         :<- [:active-project-id]
         (fn [[user-id projects active-id] [_ project-id]]
           (let [project-id (or project-id active-id)]
             (when (and user-id project-id)
               (in? (map :project-id projects) project-id)))))

(reg-sub :self/settings
         :<- [::identity]
         #(:settings %))

(reg-sub :self/ui-theme
         :<- [:self/settings]
         #(if (= (:ui-theme %) "Dark")
            "Dark" "Default"))

(reg-sub :self/dark-theme?
         :<- [:self/ui-theme]
         #(= % "Dark"))

(def-action :user/change-settings
  :uri (fn [_] "/api/change-user-settings")
  :content (fn [changes] {:changes changes})
  :process (fn [{:keys [db]} _ {:keys [settings]}]
             (let [old-settings (get-in db [:state :identity :settings])]
               (if (not= (:ui-theme settings)
                         (:ui-theme old-settings))
                 {:reload-page [true]}
                 {:db (assoc-in db [:state :identity :settings] settings)}))))

(def-action :session/change-settings
  :uri (fn [_] "/api/auth/change-session-settings")
  :content (fn [settings] {:settings settings})
  :process (fn [{:keys [db]} _ {:keys [settings]}]
             (when (nil? (current-user-id db))
               (let [cur-theme (util/ui-theme-from-dom-css)
                     new-theme (or (:ui-theme settings) "Default")
                     theme-changed? (and cur-theme (not= cur-theme new-theme))]
                 (if theme-changed?
                   {:reload-page [true]}
                   {:db (assoc-in db [:state :identity :settings] settings)})))))

(def-action :user/delete-account
  :uri (fn [_] "/api/delete-user")
  :content (fn [verify-user-id] {:verify-user-id verify-user-id})
  :process (fn [{:keys [db]} _ _]
             {:db (-> (assoc-in db [:state :identity] nil)
                      (dissoc-in [:state :self]))
              :reset-data true
              :nav ["/"]
              :dispatch [:fetch [:identity]]}))

(def-action :user/delete-member-labels
  :uri (fn [_ _] "/api/delete-member-labels")
  :content (fn [project-id verify-user-id]
             {:project-id project-id :verify-user-id verify-user-id})
  :process (fn [_ _ _] {:reset-data true}))


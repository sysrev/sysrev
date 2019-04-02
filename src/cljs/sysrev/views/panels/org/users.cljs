(ns sysrev.views.panels.org.users
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.panels.user.profile :refer [UserPublicProfileLink Avatar]]
            [sysrev.views.semantic :refer [Segment Table TableHeader TableBody TableRow TableCell Search]])
  (:require-macros [reagent.interop :refer [$]]))

(def ^:private panel [:org :users])

(def state (r/cursor app-db [:state :panel panel]))

(defn get-org-users!
  []
  (let [org-users (r/cursor state [:org-users])
        retrieving? (r/cursor state [:retrieving-org-users?])
        error (r/cursor state [:retrieving-org-users-error])]
    (reset! retrieving? true)
    (GET (str "/api/org/" @(subscribe [:current-org]) "/users")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving? false)
                     (reset! org-users (get-in response [:result :users])))
          :error-handler (fn [error-response]
                           (reset! retrieving? false)
                           (reset! error (get-in error-response [:response :error :messaage])))})))

(defn UserRow
  [{:keys [user-id email]}]
  [TableRow
   [TableCell
    [Avatar {:user-id user-id}]
    [UserPublicProfileLink {:user-id user-id :display-name (first (clojure.string/split email #"@"))}]]
   [TableCell ;; permissions and setting them go here
    ]])

(defn UsersTable
  [users]
  (when-not (empty? @users)
    [Table {:basic "true"}
     #_[TableHeader
      [TableRow
       [TableCell ;; select all goes here
        ]
       [TableCell ;; filter by row goes herev
        ]]]
     [TableBody
      (map (fn [user]
             ^{:key (:user-id user)}
             [UserRow user])
           @users)]]))

(defn user-suggestions!
  [term]
  (let [retrieving? (r/cursor state [:search-loading?])
        user-search-results (r/cursor state [:user-search-results])]
    (reset! retrieving? true)
    (GET "/api/users/search"
         {:params {:term term}
          :handler (fn [response]
                     (reset! retrieving? false)
                     (reset! user-search-results (get-in response [:result :users])))
          :error-handler (fn [response]
                           (reset! retrieving? false)
                           ($ js/console log "[sysrev.views.panels.org.users/user-suggestions] Error retrieving search results"))})))

(defn OrgUsers
  []
  (let [org-users (r/cursor state [:org-users])
        search-loading? (r/cursor state [:search-loading?])
        user-search-results (r/cursor state [:user-search-results])
        user-search-value (r/cursor state [:user-search-value])]
    (r/create-class
     {:reagent-render
      (fn [this]
        (get-org-users!)
        [:div
         [Search {:loading @search-loading?
                  :on-result-select (fn [e value]
                                      (let [result (-> value
                                                       (js->clj :keywordize-keys true)
                                                       :result)]
                                        (reset! user-search-value (first (clojure.string/split (:email result) #"@")))))
                  :on-search-change (fn [e value]
                                      (let [input-value (-> value
                                                            (js->clj :keywordize-keys true)
                                                            :value)]
                                        (reset! user-search-results [])
                                        (reset! user-search-value input-value)
                                        (user-suggestions! input-value)))
                  :result-renderer (fn [item]
                                     (let [item (js->clj item :keywordize-keys true)]
                                       (r/as-component [:div {:style {:display "flex"}} [Avatar {:user-id (:user-id item)}] [:p (first (clojure.string/split (:email item) #"@"))]])))
                  :results @user-search-results
                  :value @user-search-value}]
         [UsersTable org-users]])
      :get-initial-state
      (fn [this]
        (reset! user-search-value "")
        {})
      :component-did-mount
      (fn [this]
        (reset! user-search-value ""))
      })))


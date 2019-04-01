(ns sysrev.views.panels.org.users
  (:require [ajax.core :refer [GET]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.semantic :refer [Segment]])
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

(defn OrgUsers
  []
  (r/create-class
   {:reagent-render
    (fn [this]
      (when-not (nil? @(subscribe [:csrf-token]))
        (get-org-users!))
      [:h1 "Foo"])}))

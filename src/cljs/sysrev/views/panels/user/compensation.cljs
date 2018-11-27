(ns sysrev.views.panels.user.compensation
  (:require [ajax.core :refer [POST GET DELETE PUT]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe]]
            [re-frame.db :refer [app-db]]))

(def ^:private panel [:user :compensation])

(def state (r/cursor app-db [:state :panels panel]))

(defn payments-owed
  "Retrieve the current amount of compensation owed to user"
  [state]
  (let [user-id @(subscribe [:self/user-id])
        payments-owed (r/cursor state [:payments-owed])
        retrieving-payments-owed? (r/cursor state [:retrieving-payments-owed?])
        error-message (r/cursor state [:retrieving-payments-error-message])]
    (reset! retrieving-payments-owed? true)
    (GET (str "/api/user/" user-id "/payments-owed")
         {:headers {"x-csrf-token" @(subscribe [:csrf-token])}
          :handler (fn [response]
                     (reset! retrieving-payments-owed? false)
                     (reset! payments-owed (get-in response [:result :payments-owed])))
          :error-handler (fn [error-response]
                           (reset! retrieving-payments-owed? false)
                           (reset! error-message (get-in error-response [:response :error :message])))})))

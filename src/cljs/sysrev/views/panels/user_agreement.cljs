(ns sysrev.views.panels.user-agreement
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db]]
            [sysrev.nav :as nav]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.markdown :as md]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil])
  (:require-macros [sysrev.macros :refer [with-loader sr-defroute]]))

(def panel [:user-agreement])

(def-data :user-agreement-markdown
  :prereqs (constantly nil)
  :loaded? (fn [db] (contains? (:data db) :user-agreement-markdown))
  :uri (fn [] "/api/terms-of-use.md")
  :content-type "text/plain"
  :process (fn [{:keys [db response]} _ _]
             {:db (assoc-in db [:data :user-agreement-markdown] response)})
  :on-error (fn [{:keys [db error response]} _ _]
              {:db (assoc-in db [:data :user-agreement-markdown]
                             (str "# Error retrieving document\n\n"
                                  "## " (:status error) " " (:message error)))}))

(reg-sub :user-agreement-markdown #(get-in % [:data :user-agreement-markdown]))

(defn UserAgreement []
  (with-loader [[:user-agreement-markdown]] {}
    (when-let [content @(subscribe [:user-agreement-markdown])]
      [:div.ui.segment.user-agreement
       [md/RenderMarkdown content]])))

(defmethod panel-content panel []
  (fn [child] [UserAgreement]))

(sr-defroute user-agreement "/user-agreement" []
             (dispatch [:set-active-panel panel]))

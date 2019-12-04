(ns sysrev.views.panels.terms-of-use
  (:require [re-frame.core :refer [subscribe dispatch reg-sub]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.views.base :refer [panel-content]]
            [sysrev.markdown :as md]
            [sysrev.macros :refer-macros [with-loader sr-defroute setup-panel-state]]))

;; for clj-kondo
(declare panel)

(setup-panel-state panel [:terms-of-use])

(defn error-content-md [& [{:keys [status message] :as error}]]
  (str "# Error retrieving document"
       (when error
         (str "\n\n## " status " " message))))

(def-data :terms-of-use-md
  :prereqs (constantly nil)
  :loaded? (fn [db] (contains? (:data db) :terms-of-use-md))
  :uri (fn [] "/api/terms-of-use.md")
  :content-type "text/plain"
  :process (fn [{:keys [db response]} _ _]
             {:db (assoc-in db [:data :terms-of-use-md] response)})
  :on-error (fn [{:keys [db error response]} _ _]
              {:db (assoc-in db [:data :terms-of-use-md] (error-content-md error))}))

(reg-sub :terms-of-use-md #(get-in % [:data :terms-of-use-md]))

(defn TermsOfUsePanel []
  (with-loader [[:terms-of-use-md]] {}
    (let [content (or @(subscribe [:terms-of-use-md])
                      (error-content-md))]
      [:div.ui.segment.terms-of-use
       [md/RenderMarkdown content]])))

(defmethod panel-content panel []
  (fn [_child] [TermsOfUsePanel]))

(sr-defroute terms-of-use "/terms-of-use" []
             (dispatch [:set-active-panel panel]))

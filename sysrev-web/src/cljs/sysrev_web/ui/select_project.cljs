(ns sysrev-web.ui.select-project
  (:require [sysrev-web.base :refer [state]]
            [sysrev-web.state.core :as s]
            [sysrev-web.state.data :as d]
            [sysrev-web.ajax :as ajax]
            [sysrev-web.util :refer [full-size?]])
  (:require-macros [sysrev-web.macros :refer [with-mount-hook]]))

(defn select-project-page []
  (let [projects (d/data :all-projects)
        active-id (or (s/page-state :selected)
                      (s/active-project-id))]
    (with-mount-hook
      #(.checkbox (js/$ ".ui.radio.checkbox"))
      [:div
       [:div.ui.top.attached.header.segment
        [:h3 "Switch to project"]]
       [:div.ui.bottom.attached.segment
        [:div.ui.relaxed.divided.large.list
         (doall
          (->> projects
               (map
                (fn [[project-id project]]
                  ^{:key {:select-project project-id}}
                  [:div.item
                   [:div.ui.radio.checkbox
                    {:on-click
                     (fn [_]
                       (swap!
                        state
                        #(assoc-in % [:page :select-project :selected]
                                   project-id)))}
                    [:input {:type "radio"
                             :name "project"
                             :tab-index "0"
                             :default-checked
                             (if (= project-id active-id)
                               "true" nil)
                             :class "hidden"}]
                    [:label (:name project)]]]))))]
        [:div.ui.center.aligned
         [:a.ui.blue.button
          {:on-click #(ajax/select-project
                       (or (s/page-state :selected)
                           (s/active-project-id)))}
          "Go"]]]])))

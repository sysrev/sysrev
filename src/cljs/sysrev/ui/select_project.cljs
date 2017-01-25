(ns sysrev.ui.select-project
  (:require [sysrev.base :refer [state]]
            [sysrev.state.core :as s]
            [sysrev.state.data :as d]
            [sysrev.ajax :as ajax]
            [sysrev.util :refer [full-size? in?]]))

(defn select-project-page []
  (let [user-id (s/current-user-id)
        projects (d/data :all-projects)
        active-id (or (s/page-state :selected)
                      (s/active-project-id))
        self-projects (-> @state :identity :projects)]
    [:div
     (when (empty? self-projects)
       [:div.ui.green.segment
        [:h3 "Please select the project you are registering for"]])
     (when-not (empty? self-projects)
       [:div.ui.segments
        [:div.ui.top.attached.header.segment
         [:h3 "Your projects"]]
        [:div.ui.bottom.attached.segment
         (doall
          (->>
           (keys projects)
           (filter (in? self-projects))
           (map
            (fn [project-id]
              (let [project (get projects project-id)]
                ^{:key {:select-project project-id}}
                [:div.ui.middle.aligned.grid.segment
                 [:div.ui.row
                  [:div.ui.thirteen.wide.column
                   [:h4 (:name project)]]
                  [:div.ui.three.wide.column
                   [:div.ui.blue.right.floated.button
                    {:on-click #(ajax/select-project project-id)}
                    "Select"]]]])))))]])
     [:div.ui.segments
      [:div.ui.top.attached.header.segment
       [:h3 (if (empty? self-projects) "All projects" "Other projects")]]
      [:div.ui.bottom.attached.segment
       (doall
        (->>
         (keys projects)
         (remove (in? self-projects))
         (map
          (fn [project-id]
            (let [project (get projects project-id)]
              ^{:key {:join-project project-id}}
              [:div.ui.middle.aligned.grid.segment
               [:div.ui.row
                [:div.ui.thirteen.wide.column
                 [:h4 (:name project)]]
                [:div.ui.three.wide.column
                 [:div.ui.blue.right.floated.button
                  {:on-click #(ajax/join-project project-id)
                   :class (if (or (d/admin-user? user-id)
                                  (empty? self-projects))
                            "" "disabled")}
                  "Join"]]]])))))]]]))

(ns sysrev.views.panels.org.projects
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe reg-sub dispatch]]
            [sysrev.data.core :as data :refer [def-data]]
            [sysrev.views.panels.org.main :as org]
            [sysrev.util :as util :refer [index-by]]
            [sysrev.macros :refer-macros [setup-panel-state def-panel]]))

;; for clj-kondo
(declare panel state)

(setup-panel-state panel [:org :projects]
                   :state state :get [panel-get ::get] :set [panel-set ::set])

(def-data :org/projects
  :loaded?  (fn [db org-id]
              (-> (get-in db [:org org-id])
                  (contains? :projects)))
  :uri      (fn [org-id] (str "/api/org/" org-id "/projects"))
  :process  (fn [{:keys [db]} [org-id] {:keys [projects]}]
              (let [user-projects (index-by :project-id @(subscribe [:self/projects]))
                    member-of? #(get-in user-projects [% :member?])]
                {:db (-> (assoc-in db [:org org-id :projects]
                                   (for [p projects]
                                     (assoc p :member? (member-of? (:project-id p)))))
                         (panel-set :get-projects-error nil))}))
  :on-error (fn [{:keys [db error]} _ _]
              {:db (panel-set db :get-projects-error (:message error))}))

(reg-sub :org/projects
         (fn [db [_ org-id]]
           (get-in db [:org org-id :projects])))

(def-panel :uri "/org/:org-id/projects" :params [org-id]
  :on-route (let [org-id (util/parse-integer org-id)]
              (org/on-navigate-org org-id panel)))

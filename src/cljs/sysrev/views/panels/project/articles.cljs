(ns sysrev.views.panels.project.articles
  (:require [clojure.string :as str]
            [cljs-time.coerce :as tc]
            [reagent.core :as r]
            [reagent.ratom :refer [reaction]]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-sub-raw reg-event-db
              reg-event-fx trim-v]]
            [re-frame.db :refer [app-db]]
            [sysrev.views.base :refer [panel-content logged-out-content]]
            [sysrev.views.components :refer
             [selection-dropdown with-ui-help-tooltip ui-help-icon]]
            [sysrev.views.article :refer [article-info-view]]
            [sysrev.views.article-list :as al]
            [sysrev.shared.article-list :refer
             [is-resolved? is-consistent? is-single? is-conflict?]]
            [sysrev.state.ui :refer [get-panel-field]]
            [sysrev.nav :refer [nav nav-scroll-top]]
            [sysrev.state.nav :refer [project-uri]]
            [sysrev.util :refer [nbsp full-size? number-to-word time-from-epoch]]
            [sysrev.shared.util :refer [in?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(def ^:private panel [:project :project :articles])

(def initial-state {:filters []
                    :show-inclusion false
                    :show-labels :all
                    :show-notes false})
(defonce state (r/cursor app-db [:state :panels panel]))
(defn ensure-state []
  (when (nil? @state)
    (reset! state initial-state)))

(defn clear-filters [])

(defmethod al/panel-base-uri panel
  [_ project-id]
  (project-uri project-id "/articles"))

(defmethod al/article-uri panel
  [_ project-id article-id]
  (project-uri project-id (str "/articles/" article-id)))

(reg-sub
 ::default-filters
 (fn [] [(subscribe [:project/overall-label-id])])
 (fn [[overall-id]] {:label-id overall-id}))

(defmethod al/default-filters-sub panel []
  [::default-filters])

(defmethod al/list-header-tooltip panel []
  ["List of all project articles"])

(defmethod al/private-article-view? panel []
  false)

(defmethod al/reload-articles panel [_ project-id args]
  (dispatch [:reload [:project/article-list project-id args]]))

(defmethod al/auto-refresh? panel []
  false)

(reg-sub
 :project-articles/article-id
 :<- [:active-panel]
 :<- [:article-list/article-id panel]
 (fn [[active-panel article-id]]
   (when (= active-panel panel)
     article-id)))

(reg-sub
 :project-articles/editing?
 :<- [:active-panel]
 :<- [:article-list/editing? panel]
 (fn [[active-panel editing?]]
   (when (= active-panel panel)
     editing?)))

(reg-sub
 :project-articles/resolving?
 :<- [:active-panel]
 :<- [:article-list/resolving? panel]
 (fn [[active-panel resolving?]]
   (when (= active-panel panel)
     resolving?)))

(reg-event-fx
 :project-articles/show-article
 [trim-v]
 (fn [_ [article-id]]
   {:dispatch [:article-list/show-article article-id panel]}))

(reg-event-fx
 :project-articles/hide-article
 [trim-v]
 (fn []
   {:dispatch [:article-list/hide-article panel]}))

(reg-event-fx
 :project-articles/reset-filters
 [trim-v]
 (fn [_ [keep]]
   {:dispatch [::al/reset-filters keep panel]}))

(reg-event-fx
 :project-articles/set-group-status
 [trim-v]
 (fn [_ [status]]
   {:dispatch [:article-list/set-filter-value :group-status status panel]}))

(reg-event-fx
 :project-articles/set-inclusion-status
 [trim-v]
 (fn [_ [status]]
   {:dispatch [:article-list/set-filter-value :inclusion-status status panel]}))

(defmethod panel-content [:project :project :articles] []
  (fn [child]
    (when-let [project-id @(subscribe [:active-project-id])]
      [:div.project-content
       [al/article-list-view panel]
       child])))

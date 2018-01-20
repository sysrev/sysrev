(ns sysrev.views.panels.project.define-labels
  (:require
   [reagent.core :as r]
   [re-frame.core :refer
    [subscribe dispatch reg-sub reg-sub-raw
     reg-event-db reg-event-fx trim-v]]
   [re-frame.db :refer [app-db]]
   [sysrev.subs.labels :as labels]
   [sysrev.views.base :refer [panel-content]]
   [sysrev.views.components :as ui]))

(def panel [:project :project :labels :edit])

(defonce state (r/cursor app-db [:state :panels panel]))

(defn- get-labels []
  (get-in @state [:labels]))

(defn- set-labels [labels]
  (swap! state assoc-in [:labels] labels))

(defn- saved-labels []
  (let [db @app-db]
    (->> (labels/project-label-ids @app-db)
         (mapv #(labels/get-label-raw db %)))))

(defn- active-labels []
  (if (and (nil? (get-labels))
           (not-empty (saved-labels)))
    (do (set-labels (saved-labels))
        (get-labels))
    (get-labels)))

(defn- labels-changed? []
  (not= (active-labels) (saved-labels)))

(defn- revert-changes []
  (set-labels (saved-labels)))

(defn- add-empty-label []
  (let [active (active-labels)]
    (set-labels (vec (concat active [nil])))))

(defn- delete-label-index [i]
  (let [active (active-labels)]
    (set-labels (vec (concat (->> active (take i))
                             (->> active (drop (inc i))))))))

(defn- DeleteLabelButton [i label]
  [:div.ui.small.orange.icon.button
   {:on-click #(delete-label-index i)
    :style {:margin "0"}}
   [:i.remove.icon]])

(defn- LabelItem [i label]
  [:div.ui.middle.aligned.grid.segment.label-item
   [:div.row
    [ui/CenteredColumn
     [:div.ui.blue.label (str (inc i))]
     "one wide center aligned column label-index"]
    [:div.fourteen.wide.column
     (pr-str label)]
    [ui/CenteredColumn
     [DeleteLabelButton i label]
     "one wide center aligned column delete-label"]]])

(defn- AddLabelButton []
  [:div.ui.fluid.large.icon.button
   {:on-click #'add-empty-label}
   [:i.plus.circle.icon]
   "Add Label"])

(defn- SaveButton []
  (let [changed? (labels-changed?)]
    [:div.ui.large.right.labeled.positive.icon.button
     {:on-click nil
      :class (if changed? "" "disabled")}
     "Save Labels"
     [:i.check.circle.outline.icon]]))

(defn- CancelButton []
  (let [changed? (labels-changed?)]
    [:div.ui.large.right.labeled.icon.button
     {:on-click #'revert-changes
      :class (if changed? "" "disabled")}
     "Cancel"
     [:i.undo.icon]]))

(defn- SaveCancelButtons []
  [:div.ui.center.aligned.grid>div.row>div.ui.sixteen.wide.column
   [SaveButton] [CancelButton]])

(defmethod panel-content panel []
  (fn [child]
    (let [admin? (or @(subscribe [:member/admin?])
                     @(subscribe [:user/admin?]))
          active (active-labels)]
      [:div.define-labels
       [:div
        (->> active
             (map-indexed
              (fn [i label] ^{:key i} [LabelItem i label]))
             doall)]
       [AddLabelButton]
       [:div.ui.divider]
       [SaveCancelButtons]])))

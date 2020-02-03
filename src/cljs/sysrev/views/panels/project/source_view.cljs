(ns sysrev.views.panels.project.source-view
  (:require [ajax.core :refer [GET POST DELETE]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.data.cursors :refer [map-from-cursors prune-cursor]]
            [sysrev.shared.util :refer [parse-integer]]
            [sysrev.views.semantic :refer [Button Tab]]
            [sysrev.views.reagent-json-view :refer [ReactJSONView]]))

(def state (r/atom {}))

(defn save-cursors!
  [source-id cursors]
  (let [project-id @(subscribe [:active-project-id])
        saving? (r/cursor state [source-id :saving?])]
    (POST (str "/api/sources/" source-id "/cursors")
          {:params {:project-id project-id
                    :cursors cursors}
           :headers {"x-csrf-token" @(subscribe [:csrf-token])}
           :handler (fn [_]
                      (reset! saving? false)
                      (dispatch [:reload [:project/sources project-id]]))
           :error-handler (fn [_]
                            (reset! saving? false)
                            (.log js/console "[sysrev.views.panels.project.source-view] error in save-cursors! for " source-id))})))

(defn delete-cursors!
  [source-id]
  (let [project-id @(subscribe [:active-project-id])
        deleting? (r/cursor state [source-id :deleting?])]
    (DELETE (str "/api/sources/" source-id "/cursors")
            {:params {:project-id project-id}
             :headers {"x-csrf-token" @(subscribe [:csrf-token])}
             :handler (fn [_]
                        (reset! deleting? false)
                        (dispatch [:reload [:project/sources project-id]]))
             :error-handler (fn [_]
                              (reset! deleting? false)
                              (.log js/console "[sysrev.views.panels.project.source-view] error in delete-cursors! for " source-id))})))

(defn EditView
  [{:keys [json temp-cursors editing-view?]}]
  [:div
   [:div {:style {:padding-left "1em"}}
    [Button {:size "tiny"
             :style {:margin-top "0.5em"
                     :margin-right "0"}
             :onClick #(swap! editing-view? not)}
     "Stop Editing"]]
   [ReactJSONView {:json json
                   :on-add (fn [e context]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (let [ns (:ns context)
                                   cursor (->> (clojure.string/split ns #" ")
                                               (mapv #(if (parse-integer %)
                                                        (parse-integer %)
                                                        (keyword %)))
                                               prune-cursor)]
                               (swap! temp-cursors conj cursor)
                               ;; remove redundant cursors
                               (reset! temp-cursors (distinct @temp-cursors))))}]
   [:div {:style {:padding-left "1em"}}
    [Button {:size "tiny"
             :style {:margin-top "0.5em"
                     :margin-right "0"}
             :onClick #(swap! editing-view? not)}
     "Stop Editing"]]])

(defn PreviewView
  [{:keys [json temp-cursors source-id editing-view? cursors saving? deleting?]}]
  (let [on-save! (fn [_]
                   (reset! saving? true)
                   (save-cursors! source-id
                                  @temp-cursors))
        on-delete! (fn [_]
                     (reset! temp-cursors [])
                     (delete-cursors! source-id))]
    [:div
     [:div {:style {:padding-left "1em"
                    :margin-top "0.5em"}}
      [Button {:size "tiny"
               :onClick #(swap! editing-view? not)}
       "Stop Editing"]
      [Button {:size "tiny"
               :onClick on-save!
               :disabled (boolean (= @temp-cursors @cursors))
               :loading @saving?} "Save"]
      [Button {:size "tiny"
               :onClick on-delete!
               :disabled (not (seq @temp-cursors))
               :loading @deleting?} "Reset View"]]
     (if (seq @temp-cursors)
       [ReactJSONView {:json (clj->js (map-from-cursors (js->clj json :keywordize-keys true) @temp-cursors))}]
       [:div
        {:style {:padding-left "1em"}} "Entire JSON will be visible in Article View. Choose fields in 'Edit View' to narrow view."])
     [:div {:style {:padding-left "1em"}}
      [Button {:size "tiny"
               :onClick #(swap! editing-view? not)}
       "Stop Editing"]
      [Button {:size "tiny"
               :onClick on-save!
               :disabled (= @temp-cursors @cursors)
               :loading @saving?} "Save"]
      [Button {:size "tiny"
               :onClick on-delete!
               :disabled (not (seq @temp-cursors))
               :loading @deleting?} "Reset View"]]]))

(defn EditJSONView
  "Edit the JSON view for source. The editing-view? atom is passed as a prop"
  [{:keys [source editing-view?]}]
  (let [source-id (:source-id @source)
        source-name (get-in @source [:meta :source])
        project-id @(subscribe [:active-project-id])
        cursors (r/cursor source [:meta :cursors])
        sample-article (subscribe [:project/sample-article project-id source-id])
        ;; todo: CT.gov results should just be using content and not json
        json (cond (= source-name "CT.gov search")
                   (clj->js (:json @sample-article))
                   (= (:mimetype @sample-article) "application/json")
                   (.parse js/JSON @(r/cursor @sample-article :content))
                   :else "error")
        ;; change to specific temp-cursors
        temp-cursors (r/cursor state [source-id :temp-cursors])
        saving? (r/cursor state [source-id :saving?])
        deleting? (r/cursor state [source-id :deleting?])]
    (when (nil? json)
      (dispatch [:reload [:project/get-source-sample-article project-id source-id]]))
    (reset! temp-cursors (mapv #(mapv keyword %) @cursors))
    (reset! saving? false)
    (reset! deleting? false)
    [:div
     [Tab {:panes
           [{:menuItem "Edit JSON"
             :render
             (fn []
               (r/as-component
                [EditView {:json json
                           :temp-cursors temp-cursors
                           :editing-view? editing-view?}]))
             ;;:compact true
             :fluid true}
            {:menuItem "Preview Changes"
             :render (fn []
                       (r/as-component
                        [PreviewView {:json json
                                      :temp-cursors temp-cursors
                                      :cursors cursors
                                      :editing-view? editing-view?
                                      :source-id source-id
                                      :saving? saving?
                                      :deleting? deleting?}]))
             ;;:compact true
             :fluid true}]}]]))

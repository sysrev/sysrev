(ns sysrev.views.panels.project.source-view
  (:require [ajax.core :refer [POST]]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.data.cursors :refer [map-from-cursors prune-cursor]]
            [sysrev.shared.util :refer [parse-integer]]
            [sysrev.views.semantic :refer [Button]]
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

(defn ns->cursor
  "Convert a name string into a cursor"
  [s]
  (->> (clojure.string/split s #" ")
       (mapv #(if (parse-integer %)
                (parse-integer %)
                (keyword %)))
       prune-cursor))

(defn cursor->ns
  "Convert a cursor vector into a namespace string"
  [m]
  (->> m
       (map #(-> % symbol str))
       (clojure.string/join " ")))

(defn EditView
  [{:keys [json temp-cursors editing-view?]}]
  [:div
   [:div {:style {:padding-left "1em"}}
    [Button {:size "tiny"
             :style {:margin-top "0.5em"
                     :margin-right "0"}
             :onClick #(swap! editing-view? not)}
     "Cancel"]]
   [ReactJSONView json {:on-add (fn [e context]
                                  (.preventDefault e)
                                  (.stopPropagation e)
                                  (let [ns (:namespace context)
                                        cursor (ns->cursor ns)]
                                    (swap! temp-cursors conj cursor)
                                    ;; remove redundant cursors
                                    (reset! temp-cursors (distinct @temp-cursors))))}]
   [:div {:style {:padding-left "1em"}}
    [Button {:size "tiny"
             :style {:margin-top "0.5em"
                     :margin-right "0"}
             :onClick #(swap! editing-view? not)}
     "Cancel"]]])

(defn PreviewView
  [{:keys [json temp-cursors source-id editing-view? cursors saving? deleting?]}]
  (let [on-save! (fn [_]
                   (reset! saving? true)
                   (save-cursors! source-id
                                  @temp-cursors))
        on-delete! (fn [_]
                     (reset! temp-cursors []))]
    [:div
     [:div {:style {:padding-left "1em"
                    :margin-top "0.5em"}}
      [Button {:size "tiny"
               :onClick #(swap! editing-view? not)}
       "Cancel"]
      [Button {:size "tiny"
               :onClick on-save!
               :disabled (boolean (= @temp-cursors @cursors))
               :loading @saving?} "Save"]
      [Button {:size "tiny"
               :onClick on-delete!
               :disabled (not (seq @temp-cursors))
               :loading @deleting?} "Reset View"]]
     (if (seq @temp-cursors)
       [ReactJSONView (clj->js (map-from-cursors (js->clj json :keywordize-keys true) @temp-cursors))
        {:on-minus (fn [e context]
                     (.preventDefault e)
                     (.stopPropagation e)
                     (let [ns (:namespace context)
                           cursor (-> (ns->cursor ns) prune-cursor)]
                       (swap! temp-cursors (fn [m] (remove #(= % cursor) m)))))}]
       [:div
        {:style {:padding-left "1em"
                 :padding-top "1em"
                 :padding-bottom "1em"}}
        [:div "{ }"]
        [:div {:style {:padding-top "1em"}} "Choose fields from 'Default View' to include in 'Reviewer View'"]])
     [:div {:style {:padding-left "1em"}}
      [Button {:size "tiny"
               :onClick #(swap! editing-view? not)}
       "Cancel"]
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
        deleting? (r/cursor state [source-id :deleting?])
        active-tab (r/cursor state [source-id :active-tab])]
    (r/create-class
     {:reagent-render
      (fn [{:keys [source editing-view?]}]
        [:div {:style {:width "100%"}}
         [:div {:class "ui top attached tabular menu"}
          [:div {:class (clojure.string/join " " [(when (= @active-tab "edit")
                                                    "active")
                                                  "item"])
                 :on-click (fn [_]
                             (reset! active-tab "edit"))} "Default View"]
          [:div {:class (clojure.string/join " " [(when (= @active-tab "preview")
                                                    "active")
                                                  "item"])
                 :on-click #(reset! active-tab "preview")} "Reviewer View"]]
         [:div {:class (clojure.string/join " "
                                            ["ui" "bottom" "attached"
                                             (when (= @active-tab "edit") "active")
                                             "tab" ;;"segment"
                                             ])}
          [EditView {:json json
                     :temp-cursors temp-cursors
                     :editing-view? editing-view?}]]
         [:div {:class (clojure.string/join " "
                                            ["ui" "bottom" "attached"
                                             (when (= @active-tab "preview") "active")
                                             "tab" ;;"segment"
                                             ])}
          [PreviewView {:json json
                        :temp-cursors temp-cursors
                        :cursors cursors
                        :editing-view? editing-view?
                        :source-id source-id
                        :saving? saving?
                        :deleting? deleting?}]]])
      :component-will-unmount (fn [_]
                                (reset! active-tab "edit")
                                (reset! temp-cursors []))
      :component-did-mount (fn [_]
                             (reset! temp-cursors @cursors)
                             (reset! saving? false)
                             (reset! deleting? false)
                             (reset! active-tab "edit")
                             (dispatch [:reload [:project/get-source-sample-article project-id source-id]]))})))

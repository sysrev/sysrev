(ns sysrev.views.panels.project.source-view
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.data.cursors :refer [map-from-cursors prune-cursor]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.loading :as loading]
            [sysrev.views.semantic :refer [Button Icon]]
            [sysrev.views.reagent-json-view :refer [ReactJSONView]]
            [sysrev.util :as util :refer [parse-integer css]]))

(def-action :project-source/save-cursors
  :uri (fn [_ source-id _] (str "/api/sources/" source-id "/cursors"))
  :content (fn [project-id _ cursors] {:project-id project-id
                                       :cursors cursors})
  :process (fn [_ [project-id _ _] _result]
             {:dispatch [:reload [:project/sources project-id]]}))

(defn- ns->cursor
  "Convert a name string into a cursor"
  [s]
  (->> (str/split s #" ")
       (mapv #(or (parse-integer %) (keyword %)))
       prune-cursor))

(defn- EditCancelButton [editing-view?]
  [:div {:style {:padding-left "1em"}}
   [Button {:size "tiny"
            :style {:margin-top "0.5em" :margin-right "0"}
            :on-click #(swap! editing-view? not)}
    "Cancel"]])

(defn- EditView [{:keys [json temp-cursors editing-view?]}]
  [:div
   [EditCancelButton editing-view?]
   [ReactJSONView json {:on-add (fn [e context]
                                  (.preventDefault e)
                                  (.stopPropagation e)
                                  (let [cursor (-> context :namespace ns->cursor)]
                                    (swap! temp-cursors #(distinct (conj % cursor)))))}]
   [EditCancelButton editing-view?]])

(defn- PreviewView [{:keys [json temp-cursors source-id editing-view? cursors]}]
  (let [project-id @(subscribe [:active-project-id])
        buttons (fn [props]
                  [:div props
                   [Button {:size "tiny"
                            :on-click #(swap! editing-view? not)}
                    "Cancel"]
                   (let [save-action [:project-source/save-cursors
                                      project-id source-id @temp-cursors]]
                     [Button {:size "tiny"
                              :disabled (= @temp-cursors @cursors)
                              :on-click #(dispatch [:action save-action])
                              :loading (loading/action-running? save-action)}
                      "Save"])
                   [Button {:size "tiny"
                            :disabled (empty? @temp-cursors)
                            :on-click #(reset! temp-cursors [])}
                    "Reset View"]])]
    [:div
     [buttons {:style {:padding-left "1em" :margin-top "0.5em"}}]
     (if (seq @temp-cursors)
       [ReactJSONView (-> json
                          (js->clj :keywordize-keys true)
                          (map-from-cursors @temp-cursors)
                          clj->js)
        {:on-minus (fn [e context]
                     (.preventDefault e)
                     (.stopPropagation e)
                     (let [cursor (-> context :namespace ns->cursor prune-cursor)]
                       (swap! temp-cursors #(remove (partial = cursor) %))))}]
       [:div {:style {:padding-left "1em" :padding-bottom "1em"}}
        [:div {:style {:padding-top "1em"}}
         "Default View - All Fields Included. "]
        [:div {:style {:padding-top "1em"}}
         "Make selections in Available Fields to refine Review Document"]])
     [buttons {:style {:padding-left "1em"}}]]))

(defn EditJSONView
  "Edit the JSON view for source. The editing-view? atom is passed as a prop"
  [{:keys [source editing-view?]}]
  (let [project-id (subscribe [:active-project-id])
        active-tab (r/atom :edit)
        temp-cursors (r/atom (-> @source :meta :cursors))]
    (when (and @(subscribe [:member/admin?])
               @project-id
               (:source-id @source))
      (dispatch [:reload [:project-source/sample-article @project-id (:source-id @source)]]))
    (fn [{:keys [source editing-view?]}]
      (let [{:keys [source-id meta]} @source
            source-name (:source meta)
            cursors (r/cursor source [:meta :cursors])
            sample-article @(subscribe [:project-source/sample-article @project-id source-id])
            ;; todo: CT.gov results should just be using content and not json
            json (cond (= source-name "CT.gov search")
                       (clj->js (:json sample-article))
                       (= (:mimetype sample-article) "application/json")
                       (.parse js/JSON (:content sample-article))
                       :else "error")
            active? (partial = @active-tab)]
        [:div {:style {:width "100%"}}
         [:div.ui.top.attached.tabular.menu
          [:div.item {:class (css [(active? :edit) "active"])
                      :style {:cursor "pointer"}
                      :on-click #(reset! active-tab :edit)}
           "Available Fields"]
          [:div.item {:class (css [(active? :preview) "active"])
                      :style {:cursor "pointer"}
                      :on-click #(reset! active-tab :preview)}
           "Selected Fields"]
          [:a {:href "https://www.youtube.com/watch?v=" :target "_blank"
               :style {:margin-left "0.25em"}}
           [Icon {:name "video camera"}]]]
         [:div.ui.bottom.attached.tab #_ .segment
          {:class (css [(active? :edit) "active"])}
          [EditView {:json json
                     :temp-cursors temp-cursors
                     :editing-view? editing-view?}]]
         [:div.ui.bottom.attached.tab #_ .segment
          {:class (css [(active? :preview) "active"])}
          [PreviewView {:json json
                        :temp-cursors temp-cursors
                        :cursors cursors
                        :editing-view? editing-view?
                        :source-id source-id}]]]))))

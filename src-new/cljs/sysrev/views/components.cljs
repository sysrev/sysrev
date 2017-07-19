(ns sysrev.views.components
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]
   [reagent.core :as r]
   [sysrev.util :refer [url-domain nbsp time-elapsed-string]]
   [sysrev.shared.util :refer [num-to-english]]))

(defn dangerous
  "Produces a react component using dangerouslySetInnerHTML
   Ex: (dangerous :div (:abstract record))"
  ([comp content]
   (dangerous comp nil content))
  ([comp props content]
   [comp (assoc props :dangerouslySetInnerHTML {:__html content})]))

(defn labeled-input [label-text input-elt & [attrs label-attrs]]
  (let [attrs (or attrs {})
        label-attrs (or label-attrs {})]
    [:div.ui.labeled.input attrs
     [:div.ui.label label-attrs label-text]
     input-elt]))

(s/def ::tab-id keyword?)
(s/def ::content any?)
(s/def ::action (or string? fn?))
(s/def ::menu-tab
  (s/keys :req-un [::tab-id ::content ::action]))

(defn primary-tabbed-menu [entries active-tab-id & [menu-class]]
  (let [menu-class (or menu-class "")
        entries (remove nil? entries)
        n-tabs (count entries)
        n-tabs-word (num-to-english n-tabs)
        render-entry (fn [{:keys [tab-id action content] :as entry}]
                       (when entry
                         [:a {:key tab-id
                              :class (if (= tab-id active-tab-id)
                                       "active item" "item")
                              :href (when (string? action) action)
                              :on-click (when-not (string? action) action)}
                          content]))]
    [:div.ui
     {:class
      (str n-tabs-word " item " "small" " pointing menu primary-menu " menu-class)}
     (doall
      (for [entry entries]
        (render-entry entry)))]))
(s/fdef
 primary-tabbed-menu
 :args (s/cat :entries (s/coll-of ::menu-tab)
              :active-tab-id ::tab-id
              :menu-class (s/? string?)))

(defn secondary-tabbed-menu [left-entries right-entries active-tab-id & [menu-class]]
  (let [menu-class (or menu-class "")
        render-entry (fn [{:keys [tab-id action content] :as entry}]
                       (when entry
                         [:a {:key tab-id
                              :class (if (= tab-id active-tab-id)
                                       "active item" "item")
                              :href (when (string? action) action)
                              :on-click (when-not (string? action) action)}
                          content]))]
    [:div.secondary-menu-wrapper
     [:div.ui
      {:class
       (str "tiny" " secondary pointing menu secondary-menu " menu-class)}
      (doall
       (for [entry left-entries]
         (render-entry entry)))
      (when-not (empty? right-entries)
        [:div.right.menu
         (doall
          (for [entry right-entries]
            (render-entry entry)))])]]))
(s/fdef
 secondary-tabbed-menu
 :args (s/cat :left-entries (s/coll-of ::menu-tab)
              :right-entries (s/coll-of ::menu-tab)
              :active-tab-id ::tab-id
              :menu-class (s/? string?)))

(defn wrap-dropdown [elt]
  (r/create-class
   {:component-did-mount
    #(-> (js/$ (r/dom-node %))
         (.dropdown))
    :reagent-render
    (fn [elt]
      elt)}))

(defn selection-dropdown [selected-item items]
  [wrap-dropdown
   [:div.ui.selection.dropdown
    [:i.dropdown.icon]
    selected-item
    (into [:div.menu] items)]])

(defn dropdown-menu [entries & {:keys [icon-class dropdown-class label]
                                :or {icon-class "dropdown"
                                     dropdown-class "dropdown"
                                     label ""}}]
  [wrap-dropdown
   [:div.ui {:class dropdown-class}
    label
    [:i {:class (str icon-class " icon")
         :style (when-not (and (seqable? label)
                               (empty? label))
                  {:margin-left "0.7em"
                   :margin-right "0em"})}]
    [:div.menu
     (doall
      (for [{:keys [action content] :as entry} entries]
        (when entry
          ^{:key entry}
          [:a.item {:href (when (string? action) action)
                    :on-click (when-not (string? action) action)}
           content])))]]])

(defn with-tooltip [content & [popup-options]]
  (r/create-class
   {:component-did-mount
    #(.popup (js/$ (r/dom-node %))
             (clj->js
              (merge
               {:inline true
                :hoverable true
                :position "top center"
                :delay {:show 400
                        :hide 0}
                :transition "fade up"}
               (or popup-options {}))))
    :reagent-render
    (fn [content] content)}))

(defn out-link [url]
  [:div.item>a {:target "_blank" :href url}
   (url-domain url) nbsp [:i.external.icon]])

(defn updated-time-label [dt]
  [:div.ui.tiny.label (time-elapsed-string dt)])

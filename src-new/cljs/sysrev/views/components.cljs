(ns sysrev.views.components
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [re-frame.core :as re-frame :refer
    [subscribe dispatch]]
   [reagent.core :as r]
   [cljsjs.clipboard]
   [sysrev.util :refer
    [url-domain nbsp time-elapsed-string full-size?]]
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
                              :on-click (cond (vector? action)
                                              #(dispatch [:navigate action])

                                              (string? action) nil

                                              :else action)}
                          content]))]
    [:div.project-menu-wrapper
     [:div.ui
      {:class
       (str n-tabs-word " item " "small" " secondary pointing menu primary-menu " menu-class)}
      (doall
       (for [entry entries]
         (render-entry entry)))]]))
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
                              :on-click (cond (vector? action)
                                              #(dispatch [:navigate action])

                                              (string? action) nil

                                              :else action)}
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

(defn dropdown-menu [entries & {:keys [icon-class dropdown-class label style]
                                :or {icon-class "small down chevron"
                                     dropdown-class "dropdown"
                                     label ""
                                     style {}}}]
  [wrap-dropdown
   [:div.ui {:class dropdown-class :style style}
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

(defn three-state-selection [on-change curval]
  ;; nil for unset, true, false
  (let [size (if (full-size?) "large" "small")
        class (str "ui " size " buttons three-state")
        bclass (fn [secondary? selected?]
                 (str "ui " size " "
                      (cond (not selected?) ""
                            secondary?        "grey"
                            :else             "primary")
                      " icon button"))]
    [:div {:class class}
     [:div.ui {:class (bclass false (false? curval))
               :on-click #(on-change false)}
      "No"]
     [:div.ui {:class (bclass true (nil? curval))
               :on-click #(on-change nil)}
      "?"]
     [:div.ui {:class (bclass false (true? curval))
               :on-click #(on-change true)}
      "Yes"]]))

(defn three-state-selection-icons
  [on-change curval &
   {:keys [icons] :or {icons {false [:i.minus.circle.icon]
                              nil   [:i.help.circle.outline.icon]
                              true  [:i.plus.circle.icon]}}}]
  ;; nil for unset, true, false
  (let [size (if (full-size?) "large" "small")
        class (str "ui " size " buttons three-state-icon")
        bclass (fn [secondary? selected?]
                 (str "ui " size " "
                      (cond (not selected?) ""
                            secondary?        "grey"
                            :else             "black")
                      " icon button"))]
    [:div {:class class}
     [:div.ui {:class (bclass false (false? curval))
               :on-click #(on-change false)}
      (get icons false)]
     [:div.ui {:class (bclass true (nil? curval))
               :on-click #(on-change nil)}
      (get icons nil)]
     [:div.ui {:class (bclass false (true? curval))
               :on-click #(on-change true)}
      (get icons true)]]))

(defn true-false-nil-tag
  "UI component for representing an optional boolean value.
  `value` is one of true, false, nil."
  [label value &
   {:keys [size style show-icon? value color?]
    :or {size "large", style {}, show-icon? true, color? true}}]
  (let [vclass (cond
                 (not color?) ""
                 (true? value) "green"
                 (false? value) "orange"
                 (string? value) value
                 :else "")
        iclass (case value
                 true "add circle icon"
                 false "minus circle icon"
                 "help circle icon")]
    [:div.ui.label
     {:class (str vclass " " size)
      :style style}
     (str label " ")
     (when (and iclass show-icon?)
       [:i {:class iclass
            :aria-hidden true
            :style {:margin-left "0.25em"
                    :margin-right "0"}}])]))

(defn ui-help-icon [& {:keys [size] :or {size "large"}}]
  [:i.ui.grey.circle.question.mark.icon {:class size}])

(defn with-ui-help-tooltip [element & {:keys [help-content help-element]}]
  (list
   ^{:key :tooltip-content}
   [with-tooltip
    element
    {:delay {:show 400
             :hide 0}
     :hoverable false}]
   ^{:key :tooltip-help}
   [:div.ui.popup.transition.hidden.tooltip
    (cond help-content
          (doall (map-indexed #(if (string? %2)
                                 ^{:key %1}
                                 [:p %2]
                                 ^{:key %1}
                                 [:div %2])
                              help-content))
          help-element
          help-element)]))

(defn note-content-label [note-name content]
  (when (and (string? content)
             (not-empty (str/trim content))))
  [:div.ui.tiny.labeled.button.user-note
   [:div.ui.button "Notes"]
   [:div.ui.basic.label {:style {:text-align "justify"}}
    content]])

(defn clipboard-button [target child]
  (let [clip (atom nil)
        status (r/atom nil)
        transtime 1500
        default-class "ui primary button"
        success-class "ui green button"
        success-el [:span "Copied " [:i.circle.check.icon]]]
    (letfn [(reset-ui [] (reset! status nil))
            (clip-success [_]
              (reset! status true)
              (-> js/window
                  (.setTimeout reset-ui transtime)))
            (get-clipboard [el]
              (let [clip (js/Clipboard. (r/dom-node el))]
                (.on clip "success" clip-success)
                clip))
            (reset-clip! [el] (reset! clip (get-clipboard el)))
            (component-did-mount [this] (reset-clip! this))
            (component-will-update [this _] (reset-clip! this))
            (component-will-unmount []
              (when-not (nil? @clip)
                (.destroy @clip)
                (reset! clip nil)))
            (render [target child]
              [:div {:class (if @status success-class default-class)
                     :data-clipboard-target target}
               (if @status success-el child)])]
      (r/create-class
       {:display-name (str "clipboard-from-" target)
        :component-will-update component-will-update
        :component-did-mount component-did-mount
        :component-will-unmount component-will-unmount
        :reagent-render render}))))

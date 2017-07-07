(ns sysrev.ui.components
  (:require [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [sysrev.base :refer [st work-state]]
            [sysrev.util :refer
             [url-domain nbsp full-size? time-elapsed-string]]
            [sysrev.ajax :as ajax]
            [sysrev.state.core :as st :refer [data]]
            [sysrev.state.project :as project :refer [project]]
            [sysrev.state.labels :as l]
            [reagent.core :as r]
            [cljsjs.jquery]
            [cljsjs.semantic-ui])
  (:require-macros [sysrev.macros :refer [using-work-state]]))

(defn debug-container [child & children]
  (fn [child & children]
    [:div {:style {:background-color "lightgrey"}}
     child
     children]))

(defn show-debug-box
  ([title obj]
   [debug-container
    [:h1 {:key "title"} title]
    [:pre {:key "data"} (with-out-str (pprint obj))]])
  ([obj]
   (show-debug-box "" obj)))

(defn debug-box [arg & args]
  (apply show-debug-box arg args))

(defn notifier [{:keys [message class]
                 :as entry}]
  (when-not (empty? entry)
    [:div.ui.middle.aligned.large.message
     (cond->
         {:style {:color "black"
                  :font-size (when (full-size?) "125%")
                  :position "fixed"
                  :bottom "5px"
                  :min-height (if (full-size?) "60px" "30px")
                  :width "auto"
                  :right "5px"
                  :padding (if (full-size?) "1.0em" "0.6em")}}
       class (assoc :class class))
     message]))

(defn similarity-bar [similarity]
  (fn [similarity]
    (let [percent (Math/round (* 100 similarity))]
      [:div.ui.grid
       [:div.ui.row
        {:style {:padding-top "5px"
                 :padding-bottom "5px"}}
        [:div.ui.ten.wide.mobile.ten.wide.tablet.thirteen.wide.computer.column
         {:style {:padding-top "7px"
                  :padding-bottom "7px"}}
         [:div.ui.small.blue.progress
          {:style {:margin-top "6px"
                   :margin-bottom "6px"}}
          [:div.bar.middle.aligned {:style {:width (str (max percent 5) "%")}}
           [:div.progress]]]]
        [:div.ui.six.wide.mobile.six.wide.tablet.three.wide.computer.center.aligned.middle.aligned.column
         (let [color (cond (>= percent 50) "green"
                           (>= percent 20) "yellow"
                           :else "orange")
               size (if (full-size?) "large" "small")]
           [:div.ui.center.aligned.middle.aligned.label.article-predict
            {:class (str color " " size)}
            (if (full-size?)
              (str percent "% predicted inclusion")
              [:span (str percent "% Pr(include)")])])]]])))

(defn truncated-list [num coll]
  (let [show-list (take num coll)]
    (when-not (empty? coll)
      [:div.ui.list
       (doall
        (->> show-list
             (map-indexed
              (fn [idx item]
                ^{:key idx}
                (if (and (= idx (- num 1)) (< (count show-list) (count coll)))
                  [:div.item (str item " et al")]
                  [:div.item item])))))])))

(defn truncated-horizontal-list [num coll]
  (let [show-list (take num coll)
        display (str/join ", " show-list)
        extra (when (> (count coll) num) " et al")]
    (str display extra)))

(defn out-link [url]
  [:div.item
   [:a {:target "_blank" :href url}
    (url-domain url)
    nbsp
    [:i.external.icon]]])

(defn radio-button [on-click is-selected child & [is-secondary?]]
  (let [btype (when is-selected
                (if is-secondary? "grey" "primary"))
        size (if (full-size?) "large" "small")
        class (str "ui " size " " btype " icon button")
        style {}]
    [:div {:class class :style style :on-click on-click} child]))

(defn three-state-selection [change-handler curval]
  ;; nil for unset, true, false
  (fn [change-handler curval]
    (let [size (if (full-size?) "large" "small")
          class (str "ui " size " buttons three-state")]
      [:div {:class class}
       [radio-button #(change-handler false) (false? curval) "No"]
       [radio-button #(change-handler nil) (nil? curval) "?" true]
       [radio-button #(change-handler true) (true? curval) "Yes"]])))

(defn true-false-nil-tag
  "UI component for representing an optional boolean value.
  `value` is one of true, false, nil."
  [size style show-icon? label value color?]
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

(defn label-answer-tag
  "UI component for representing a label answer."
  [label-id answer]
  (let [{:keys [short-label value-type category]}
        (project :labels label-id)
        inclusion (l/label-answer-inclusion label-id answer)
        color (case inclusion
                true "green"
                false "orange"
                nil "")
        values (case value-type
                 "boolean" (if (boolean? answer)
                             [answer] [])
                 "categorical" answer
                 "numeric" answer
                 "string" answer)
        display-label (case value-type
                        "boolean" (str short-label "?")
                        short-label)]
    [:div.ui.tiny.labeled.button.label-answer-tag
     [:div.ui.button {:class color}
      (str display-label " ")]
     [:div.ui.basic.label
      (if (empty? values)
        [:i.grey.help.circle.icon
         {:style {:margin-right "0"}
          :aria-hidden true}]
        (str/join ", " values))]]))

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

(defn confirm-modal-box
  "UI component for user to confirm label submission.

  Must be triggered by (.modal (js/$ \".ui.modal\") \"show\") from the component
  containing this.

  `on-confirm` is a function that will be run when the AJAX confirm request
  succeeds."
  [on-confirm]
  (r/create-class
   {:component-did-mount
    (fn [e]
      (.modal
       (js/$ (r/dom-node e))
       (clj->js
        {;; `:detachable false` is needed to avoid a conflict between
         ;; semantic and reagent in managing DOM
         :detachable false
         :onApprove
         #(ajax/confirm-active-labels on-confirm)}))
      (.modal
       (js/$ (r/dom-node e))
       "setting" "transition" "fade up"))
    :reagent-render
    (fn [on-confirm]
      [:div.ui.small.modal
       [:div.header "Confirm article labels?"]
       (let [n-total (count (project :labels))
             n-set (->> (vals (l/active-label-values))
                        (remove nil?)
                        (remove (every-pred coll? empty?))
                        count)]
         [:div.content.confirm-modal
          [:h3.ui.header.centered
           (str
            n-set " of " n-total
            " possible labels saved for this article.")]
          [:h3.ui.header.centered
           "Click 'Confirm' to finalize and submit these labels."]])
       [:div.ui.actions
        [:div.ui.button.cancel "Cancel"]
        [:div.ui.button.ok "Confirm"]]])}))

(defn inconsistent-answers-notice [label-values]
  (let [labels (l/find-inconsistent-answers label-values)]
    (when ((comp not empty?) labels)
      [:div.ui.attached.segment.labels-warning
       [:div.ui.warning.message
        [:div.ui.horizontal.divided.list
         [:div.item
          [:div.ui.large.label
           [:i.yellow.info.circle.icon]
           "Labels inconsistent with positive inclusion value"]]
         [:div.item
          [:div.ui.horizontal.list
           (doall
            (for [label labels]
              ^{:key {:lval-warning (:label-id label)}}
              [:div.item
               [:div.ui.large.label (:short-label label)]]))]]]]])))

(defn selection-dropdown [selected-item items]
  (r/create-class
   {:component-did-mount
    (fn [el]
      (-> el
          (r/dom-node)
          (js/$)
          (.dropdown)))
    :reagent-render
    (fn [selected-item items]
      [:div.ui.selection.dropdown
       [:i.dropdown.icon]
       selected-item
       (into [:div.menu] items)])}))

(defn updated-time-label [dt]
  [:div.ui.tiny.label (time-elapsed-string dt)])

(defn dangerous
  "Produces a react component using dangerouslySetInnerHTML
   Ex: (dangerous :div (:abstract record))
  "
  ([comp content]
   (dangerous comp nil content))
  ([comp props content]
   [comp (assoc props :dangerouslySetInnerHTML {:__html content})]))

(ns sysrev-web.ui.components
  (:require [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [sysrev-web.util :refer [url-domain nbsp scroll-top full-size?]]
            [sysrev-web.base :refer [state]]
            [sysrev-web.ajax :as ajax]
            [sysrev-web.state.core :as s]
            [sysrev-web.state.data :as d :refer [data]]
            [reagent.core :as r]
            [cljsjs.jquery]
            [cljsjs.semantic-ui]))

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

(defn loading-screen []
  [:div.ui.container
   [:div.ui.stripe {:style {:padding-top "20px"}}
    [:h1.ui.header.huge.center.aligned "Loading data..."]]])

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
          class (str "ui " size " buttons")]
      [:div {:class class}
       [radio-button #(change-handler false) (false? curval) "No"]
       [radio-button #(change-handler nil) (nil? curval) "?" true]
       [radio-button #(change-handler true) (true? curval) "Yes"]])))

(defn multi-choice-selection [label-id all-values current-values on-add on-remove]
  (r/create-class
   {:component-did-mount
    #(.dropdown
      (js/$ (r/dom-node %))
      (clj->js
       {:onAdd on-add
        :onRemove on-remove}))
    :reagent-render
    (fn [label-id all-values current-values]
      [:div.ui.large.fluid.multiple.selection.dropdown
       {:style {:margin-left "6px"
                :margin-right "6px"}}
       [:input
        {:name (str "label-edit(" label-id ")")
         :value (str/join "," current-values)
         :type "hidden"}]
       [:i.dropdown.icon]
       [:div.default.text "No answer selected"]
       [:div.menu
        (doall
         (for [lval all-values]
           ^{:key {:label-option (str label-id " - " lval)}}
           [:div.item
            {:data-value (str lval)}
            (str lval)]))]])}))

(defn true-false-nil-tag
  "UI component for representing an optional boolean value.
  `value` is one of true, false, nil."
  [size style show-icon? label value]
  (let [[vclass iclass]
        (case value
          true ["green" "add circle icon"]
          false ["orange" "minus circle icon"]
          nil ["" "help circle icon"])]
    [:div.ui.label
     {:class (str vclass " " size)
      :style style}
     (str label " ")
     (when (and iclass show-icon?)
       [:i {:class iclass
            :aria-hidden true
            :style {:margin-left "0.25em"
                    :margin-right "0"}}])]))

(defn label-value-tag
  "UI component for representing the value of a label.
  `value` is one of true, false, nil."
  [label-id value]
  (let [{:keys [short-label value-type category]}
        (d/project [:labels label-id])]
    (case value-type
      "boolean"
      [true-false-nil-tag "medium" {} true
       (str short-label "?") value]
      "categorical"
      (if (= category "inclusion criteria")
        (let [inclusion (d/label-answer-inclusion label-id value)]
          [true-false-nil-tag "medium" {} true
           (str short-label
                (if (empty? value)
                  ""
                  (str " ("
                       (str/join "; " value)
                       ")")))
           inclusion])
        [:div])
      [:div])))

(defn with-tooltip [content]
  (r/create-class
   {:component-did-mount
    #(.popup (js/$ (r/dom-node %)))
    :reagent-render
    (fn [content] content)}))

(defn confirm-modal-box
  "UI component for user to confirm label submission.

  Must be triggered by (.modal (js/$ \".ui.modal\") \"show\") from the component
  containing this.

  `labels-path` is the path of keys in `state` containing active label values.

  `on-confirm` is a function that will be run when the AJAX confirm request
  succeeds."
  [article-id-fn labels-path on-confirm]
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
         #(let [article-id (article-id-fn)]
            (ajax/confirm-labels
             article-id
             (d/active-label-values article-id labels-path)
             on-confirm))}))
      (.modal
       (js/$ (r/dom-node e))
       "setting" "transition" "fade up"))
    :reagent-render
    (fn [article-id-fn labels-path on-confirm]
      [:div.ui.small.modal
       [:div.header "Confirm article labels?"]
       (let [article-id (article-id-fn)
             labels (d/project :labels)
             n-total (count labels)
             label-values (d/active-label-values article-id labels-path)
             n-set (->> label-values vals (remove nil?) count)]
         [:div.content.confirm-modal
          [:h3.ui.header.centered
           (str "You have set "
                n-set " of " n-total
                " possible labels for this article.")]
          [:h3.ui.header.centered
           "Click 'Confirm' to finalize and submit these labels."]
          [:h3.ui.header.centered
           "You will not be able to edit them later."]])
       [:div.ui.actions
        [:div.ui.button.cancel "Cancel"]
        [:div.ui.button.ok "Confirm"]]])}))

(defn project-wrapper-div [content]
  [:div
   {:style {:margin-top "-1.0em"}}
   [:div.ui.middle.aligned.center.aligned.blue.segment
    {:style {:margin-top "0"
             :padding-top "0.5em"
             :padding-bottom "0.5em"}}
    [:h4 (d/data [:all-projects (s/active-project-id) :name])]]
   content])

(defn dangerous
  "Produces a react component using dangerouslySetInnerHTML
   Ex: (dangerous :div (:abstract record))
  "
  ([comp content]
   (dangerous comp nil content))
  ([comp props content]
   [comp (assoc props :dangerouslySetInnerHTML {:__html content})]))

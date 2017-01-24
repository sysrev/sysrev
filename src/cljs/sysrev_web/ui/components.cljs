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
        :onRemove on-remove
        :onChange (fn [_] (.dropdown
                           (js/$ (r/dom-node %))
                           "hide"))}))
    :reagent-render
    (fn [label-id all-values current-values on-add on-remove]
      (let [dom-id (str "label-edit-" label-id)]
        [:div.ui.large.fluid.multiple.selection.dropdown
         {:id dom-id
          :style {:margin-left "6px"
                  :margin-right "6px"}
          ;; hide dropdown on click anywhere in main dropdown box
          :on-click #(when (or (= dom-id (-> % .-target .-id))
                               (-> (js/$ (-> % .-target))
                                   (.hasClass "default"))
                               (-> (js/$ (-> % .-target))
                                   (.hasClass "label")))
                       (let [dd (js/$ (str "#" dom-id))]
                         (when (.dropdown dd "is visible")
                           (.dropdown dd "hide"))))}
         [:input
          {:name (str "label-edit(" label-id ")")
           :value (str/join "," current-values)
           :type "hidden"}]
         [:i.dropdown.icon]
         (if (d/project [:labels label-id :required])
           [:div.default.text
            "No answer selected "
            [:span.default {:style {:font-weight "bold"}}
             "(required)"]]
           [:div.default.text "No answer selected"])
         [:div.menu
          (doall
           (for [lval all-values]
             ^{:key {:label-option (str label-id " - " lval)}}
             [:div.item
              {:data-value (str lval)}
              (str lval)]))]]))}))

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
        (d/project [:labels label-id])
        inclusion (d/label-answer-inclusion label-id answer)
        color (case inclusion
                true "green"
                false "orange"
                nil "")
        values (case value-type
                 "boolean" (if (boolean? answer)
                             [answer] [])
                 "categorical" answer)
        display-label (case value-type
                        "boolean" (str short-label "?")
                        short-label)
        style {:margin-left "4px"
               :margin-right "4px"
               :margin-top "3px"
               :margin-bottom "4px"}]
    [:div.ui.labeled.button
     {:style style}
     [:div.ui.small.button
      {:class color}
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
                        :hide 100}
                :transition "fade up"}
               (or popup-options {}))))
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
             n-set (->> (vals label-values)
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

(defn project-wrapper-div [content]
  [:div
   {:style {:margin-top "-1.0em"}}
   [:div.ui.middle.aligned.center.aligned.blue.segment
    {:style {:margin-top "0"
             :padding-top "0.5em"
             :padding-bottom "0.5em"}}
    [:h4 (d/data [:all-projects (s/active-project-id) :name])]]
   content])

(defn inconsistent-answers-notice [label-values]
  (let [labels (d/find-inconsistent-answers label-values)]
    (when ((comp not empty?) labels)
      [:div.ui.yellow.secondary.segment
       ;; {:style {:padding-top "0px"}}
       {:style {:padding-top "5px"
                :padding-bottom "5px"
                :padding-left "8px"
                :padding-right "8px"}}
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
              [:div.ui.large.label (:short-label label)]]))]]]])))

(defn dangerous
  "Produces a react component using dangerouslySetInnerHTML
   Ex: (dangerous :div (:abstract record))
  "
  ([comp content]
   (dangerous comp nil content))
  ([comp props content]
   [comp (assoc props :dangerouslySetInnerHTML {:__html content})]))

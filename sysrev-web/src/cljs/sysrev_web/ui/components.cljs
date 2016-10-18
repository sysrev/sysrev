(ns sysrev-web.ui.components
  (:require [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [sysrev-web.util :refer [url-domain nbsp scroll-top]]
            [sysrev-web.base :refer [state]]
            [sysrev-web.ajax :as ajax]
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

(defn notifier [head]
  (fn [head]
    (when-not (empty? head)
      [:div.ui.middle.aligned.large.warning.message
       {:style {:position "fixed"
                :bottom "0px"
                :height "100px"
                :width "auto"
                :right "0px"}}
       head])))


(defn similarity-bar [similarity]
  (fn [similarity]
    (let [percent (Math/round (* 100 similarity))]
      [:div.ui.grid
       [:div.ui.row
        {:style {:padding-top "5px"
                 :padding-bottom "5px"}}
        [:div.ui.thirteen.wide.column
         {:style {:padding-top "7px"
                  :padding-bottom "7px"}}
         [:div.ui.small.blue.progress
          {:style {:margin-top "6px"
                   :margin-bottom "6px"}}
          [:div.bar.middle.aligned {:style {:width (str (max percent 5) "%")}}
           [:div.progress]]]]
        [:div.ui.three.wide.center.aligned.middle.aligned.column
         (let [color (cond (>= percent 50) "green"
                           (>= percent 20) "yellow"
                           :else "orange")]
           [:div.ui.center.aligned.middle.aligned.large.label.article-predict
            {:class color}
            (str percent "% predicted inclusion")])]]])))

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
  [:a.item {:target "_blank" :href url}
   (url-domain url)
   nbsp
   [:i.external.icon]])

(defn radio-button [on-click is-selected child & [is-secondary?]]
  (let [class (when is-selected
                (if is-secondary? "grey" "primary"))]
    [:div.ui.icon.button {:class class :on-click on-click} child]))

(defn three-state-selection [change-handler curval]
  ;; nil for unset, true, false
  (fn [change-handler curval]
    [:div.ui.large.buttons
     [radio-button #(change-handler false) (false? curval) "No"]
     [radio-button #(change-handler nil) (nil? curval) "?" true]
     [radio-button #(change-handler true) (true? curval) "Yes"]]))

(defn label-value-tag
  "UI component for representing the value of a criteria label.
  `value` is one of true, false, nil."
  [criteria-id value]
  (let [[vclass iclass] (case value
                          true ["green" "fa-check-circle-o"]
                          false ["orange" "fa-times-circle-o"]
                          nil ["grey" "fa-question-circle-o"])
        label (get-in @state [:data :criteria criteria-id :short_label])]
    [:div.ui.small.label {:class vclass}
     (str label "? ")
     (when iclass
       [:i.fa.fa-lg {:class iclass :aria-hidden true}])]))

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
  [article-id labels-path on-confirm]
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
         #(ajax/confirm-labels
           article-id
           (d/active-label-values article-id labels-path)
           on-confirm)}))
      (.modal
       (js/$ (r/dom-node e))
       "setting" "transition" "fade up"))
    :reagent-render
    (fn [article-id labels-path on-confirm]
      [:div.ui.small.modal
       [:div.header "Confirm article labels?"]
       (let [criteria (data :criteria)
             n-total (count criteria)
             label-values (d/active-label-values article-id labels-path)
             n-set (->> label-values vals (remove nil?) count)]
         [:div.content
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

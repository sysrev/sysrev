(ns sysrev-web.ui.components
  (:require [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [sysrev-web.util :refer [url-domain nbsp]]))

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
        {:style {:padding-bottom "10px"}}
        [:div.ui.twelve.wide.column
         [:div.ui.tiny.blue.progress
          [:div.bar.middle.aligned {:style {:width (str (max percent 5) "%")}}
           [:div.progress]]]]
        [:div.ui.four.wide.column
         [:div.right.aligned
          (str "(" percent "% similarity to included articles)")]]]])))

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

(defn radio-button [on-click is-selected child]
  (let [class (when is-selected "primary")]
    [:div.ui.icon.button {:class class :on-click on-click} child]))

(defn three-state-selection [change-handler curval]
  ;; nil for unset, true, false
  (fn [change-handler curval]
    [:div.ui.large.buttons
     [radio-button #(change-handler false) (false? curval) "No"]
     [radio-button #(change-handler nil) (nil? curval) "?"]
     [radio-button #(change-handler true) (true? curval) "Yes"]]))

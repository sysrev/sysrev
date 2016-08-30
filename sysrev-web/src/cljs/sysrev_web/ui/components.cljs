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

(defn similarity-bar [score percent]
  (fn [score percent]
    [:div.ui.tiny.blue.progress
     [:div.bar.middle.aligned {:style {:width (str (max percent 5) "%")}}
      [:div.progress]]]))

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

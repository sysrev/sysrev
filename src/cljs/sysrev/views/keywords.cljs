(ns sysrev.views.keywords
  (:require [clojure.string :as str]
            [re-frame.core :refer [subscribe dispatch]]
            [sysrev.views.components.core :as ui]
            [sysrev.util :refer [full-size? nbsp]]))

(defn- KeywordTooltip [content label-name label-value]
  [ui/Tooltip
   {:hoverable false
    :mouse-enter-delay 50
    :mouse-leave-delay 0
    :transition "fade up"
    :distance-away 6
    :basic true
    :trigger content
    :tooltip [:div.ui.grid.keyword-popup
              [:div.middle.aligned.center.aligned.row.keyword-popup-header
               [:div.ui.sixteen.wide.column
                [:span "Set label"]]]
              [:div.middle.aligned.center.aligned.row
               [:div.middle.aligned.center.aligned.three.wide.column.keyword-side
                [:i.fitted.large.grey.exchange.icon]]
               [:div.thirteen.wide.column.keyword-popup
                [:div.ui.center.aligned.grid.keyword-popup
                 [:div.ui.row.label-name (str label-name)]
                 [:div.ui.row.label-separator]
                 [:div.ui.row.label-value (str label-value)]]]]]}])

(defn- render-keyword
  [{:keys [keyword-id text]} article-id
   & [{:keys [keywords editing? full-size? show-tooltip? label-class]}]]
  (let [{:keys [label-id label-value category] :as kw}
        (and keyword-id (get keywords keyword-id))]
    (if (nil? kw)
      [(ui/dangerous :span (if (empty? text) nbsp text))]
      (let [has-value? ((comp not nil?) label-value)
            label-name @(subscribe [:label/name nil label-id])
            enabled? @(subscribe [:label/enabled? "na" label-id])
            class (cond (= category "include")
                        (str "ui keyword include-label green basic "
                             label-class " button")
                        (= category "exclude")
                        (str "ui keyword exclude-label orange basic "
                             label-class " button")
                        :else "")
            span-content
            [:span
             " "
             (ui/dangerous
              :span
              {:class class
               :on-click
               (when (and enabled? has-value? editing?)
                 ;; this is broken
                 #(dispatch [:review/trigger-enable-label-value
                             article-id "na" label-id "na" label-value]))}
              text)
             " "]]
        (if (and show-tooltip? enabled? editing? full-size?)
          [KeywordTooltip span-content label-name label-value]
          [span-content])))))

(defn render-keywords [article-id content &
                       [{:keys [show-tooltip? label-class]
                         :or {show-tooltip? true label-class "small"}}]]
  (let [keywords @(subscribe [:project/keywords])
        editing? @(subscribe [:review/editing?])
        full-size? (full-size?)]
    (vec (concat [:span]
                 (->> content
                      (mapv #(render-keyword % article-id
                                             {:keywords keywords
                                              :editing? editing?
                                              :full-size? full-size?
                                              :show-tooltip? show-tooltip?
                                              :label-class label-class}))
                      (apply concat)
                      vec)))))

(defn render-abstract [article-id]
  (let [sections @(subscribe [:article/abstract-render article-id])]
    [:div.article-abstract
     (doall
      (map-indexed (fn [idx {:keys [name content]}]
                     (if name
                       ^{:key [article-id idx]}
                       [:div [:strong (-> name str/trim str/capitalize)]
                        ": " [render-keywords article-id content]]
                       ^{:key [article-id idx]}
                       [:div [render-keywords article-id content]]))
                   sections))]))

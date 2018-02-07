(ns sysrev.views.keywords
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [re-frame.core :as re-frame :refer [subscribe dispatch]]
   [sysrev.views.components :refer [with-tooltip dangerous]]
   [sysrev.util :refer [full-size?]]))

(defn- with-keyword-tooltip [content label-name label-value]
  [[with-tooltip content
    {:delay {:show 50
             :hide 0}
     :hoverable false
     :transition "fade up"
     :distanceAway 8
     :variation "basic"}]
   [:div.ui.inverted.grid.popup.transition.hidden.keyword-popup
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
       [:div.ui.row.label-value (str label-value)]]]]]])

(defn- render-keyword
  [{:keys [keyword-id text] :as entry} article-id
   & [{:keys [keywords editing? full-size? show-tooltip? label-class]}]]
  (let [{:keys [label-id label-value category] :as kw}
        (and keyword-id (get keywords keyword-id))]
    (if (nil? kw)
      [(dangerous :span text)]
      (let [has-value? ((comp not nil?) label-value)
            label-name @(subscribe [:label/name label-id])
            enabled? @(subscribe [:label/enabled? label-id])
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
             (dangerous
              :span
              {:class class
               :on-click
               (when (and enabled? has-value? editing?)
                 #(dispatch [:review/trigger-enable-label-value
                             article-id label-id label-value]))}
              text)
             " "]]
        (if (and show-tooltip? enabled? editing? full-size?)
          (with-keyword-tooltip
            span-content label-name label-value)
          [span-content])))))

(defn render-keywords
  [article-id content & [{:keys [show-tooltip? label-class]
                          :or {show-tooltip? true label-class "small"}}]]
  (let [keywords @(subscribe [:project/keywords])
        editing? @(subscribe [:review/editing?])
        full-size? (full-size?)]
    (vec
     (concat
      [:span]
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

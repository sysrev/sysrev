(ns sysrev.views.article
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [re-frame.core :as re-frame :refer [subscribe dispatch]]
   [sysrev.views.keywords :refer [render-keywords render-abstract]]
   [sysrev.views.components :refer [out-link]]
   [sysrev.views.labels :refer
    [label-values-component article-labels-view]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn- author-names-text [nmax coll]
  (let [show-list (take nmax coll)
        display (str/join ", " show-list)
        extra (when (> (count coll) nmax) " et al")]
    (str display extra)))

(defn- review-status-label [status]
  (let [resolving? @(subscribe [:review/resolving?])
        sstr
        (cond (= status :resolved)     "Resolved"
              resolving?               "Resolving conflict"
              (= status :conflict)     "Conflicting labels"
              (= status :single)       "Reviewed by one user"
              (= status :consistent)   "Consistent labels"
              (= status :unreviewed)   "Not yet reviewed"
              :else                    nil)
        color
        (cond (= status :resolved)     "purple"
              (= status :conflict)     "orange"
              (= status :consistent)   "green"
              :else                    "")]
    (when sstr
      [:div.ui.basic.label
       {:class color
        :style {:margin-top "-3px"
                :margin-bottom "-3px"
                :margin-right "0px"}}
       (str sstr)])))

(defn article-info-main-content [article-id]
  (with-loader [[:article article-id]] {}
    (let [authors @(subscribe [:article/authors article-id])
          journal-name @(subscribe [:article/journal-name article-id])
          abstract @(subscribe [:article/abstract article-id])
          title-render @(subscribe [:article/title-render article-id])
          journal-render @(subscribe [:article/journal-render article-id])
          urls @(subscribe [:article/urls article-id])]
      [:div
       [:h3.header
        [render-keywords article-id title-render
         {:label-class "large"}]]
       (when-not (empty? journal-name)
         [:h3.header {:style {:margin-top "0px"}}
          [render-keywords article-id journal-render
           {:label-class "large"}]])
       (when-not (empty? authors)
         [:h5.header {:style {:margin-top "0px"}}
          (author-names-text 5 authors)])
       (when-not (empty? abstract)
         [render-abstract article-id])
       ;; article file links went here (article-docs-component)
       (when-not (empty? urls)
         [:div.content.ui.list
          (doall
           (map-indexed (fn [idx url]
                          ^{:key [idx]} [out-link url])
                        urls))])])))

(defn article-info-view
  [article-id & {:keys [show-labels?]
                 :or {show-labels? false}}]
  (let [status @(subscribe [:article/review-status article-id])]
    (with-loader [[:article article-id]] {:dimmer true :min-height "500px"}
      [:div
       [:div.ui.top.attached.middle.aligned.segment
        [:div {:style {:float "left"}}
         [:h4 "Article info"]]
        (when status
          [:div {:style {:float "right"}}
           [review-status-label status]])
        [:div {:style {:clear "both"}}]]
       [:div.ui.bottom.attached.segment
        [article-info-main-content article-id]]
       (when (= show-labels? :all)
         [article-labels-view article-id])])))

(ns sysrev.views.article
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [re-frame.core :as re-frame :refer [subscribe dispatch]]
   [sysrev.views.keywords :refer [render-keywords render-abstract]]
   [sysrev.views.components :refer [out-link]]
   [sysrev.views.labels :refer
    [label-values-component article-labels-view]]
   [sysrev.util :refer [full-size?]])
  (:require-macros [sysrev.macros :refer [with-loader]]))

(defn- author-names-text [nmax coll]
  (let [show-list (take nmax coll)
        display (str/join ", " show-list)
        extra (when (> (count coll) nmax) " et al")]
    (str display extra)))

(defn- review-status-label [status]
  (let [resolving? @(subscribe [:review/resolving?])
        sstr
        (cond (= status :user)         "User view"
              (= status :resolved)     "Resolved"
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
      [:div.ui.basic.label.review-status
       {:class color}
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
  [article-id & {:keys [show-labels? private-view?]}]
  (let [status @(subscribe [:article/review-status article-id])]
    [:div
     (with-loader [[:article article-id]]
       {:class "ui segments article-info"}
       (list
        [:div.ui.top.attached.middle.aligned.header
         {:key [:article-header]}
         [:div {:style {:float "left"}}
          [:h4 "Article Info"]]
         (when (or status private-view?)
           [:div {:style {:float "right"}}
            [review-status-label (if private-view? :user status)]])
         [:div {:style {:clear "both"}}]]
        [:div.ui.attached.segment
         {:key [:article-content]}
         [article-info-main-content article-id]]))
     (when show-labels?
       [article-labels-view article-id :self-only? private-view?])]))

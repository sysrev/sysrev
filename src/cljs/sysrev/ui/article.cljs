(ns sysrev.ui.article
  (:require
   [clojure.core.reducers :refer [fold]]
   [clojure.string :as str]
   [sysrev.base :refer [state]]
   [sysrev.state.core :as s]
   [sysrev.shared.util :refer [map-values re-pos]]
   [sysrev.shared.keywords :refer [process-keywords format-abstract]]
   [sysrev.ui.components :refer
    [similarity-bar truncated-horizontal-list out-link label-answer-tag
     with-tooltip three-state-selection multi-choice-selection dangerous]]
   [sysrev.util :refer [full-size? mobile? in?]]
   [sysrev.ajax :as ajax]
   [reagent.core :as r]
   [sysrev.state.data :as d]))

(defn active-labels-path []
  (case (s/current-page)
    :classify [:page :classify :label-values]
    :article [:page :article :label-values]
    nil))

(defn enable-label-value [article-id label-id label-value]
  (let [labels-path (active-labels-path)
        {:keys [value-type]} (d/project-label label-id)
        active-values (d/active-label-values article-id labels-path)]
    (cond (= value-type "boolean")
          (swap! state assoc-in
                 (concat labels-path [label-id])
                 label-value)
          (= value-type "categorical")
          (do
            (.dropdown
             (js/$ (str "#label-edit-" label-id))
             "set selected"
             label-value)
            (swap! state assoc-in
                   (concat labels-path [label-id])
                   (-> (conj (get
                              (d/active-label-values article-id labels-path)
                              label-id)
                             label-value)
                       distinct vec))))))

(defn render-keywords
  [article-id content & [{:keys [label-class show-tooltip]
                          :or {label-class "small button"
                               show-tooltip true}}]]
  (let [keywords (d/project-keywords)]
    (vec
     (concat
      [:span]
      (->> content
           (mapv (fn [{:keys [keyword-id text]}]
                   (let [kw (and keyword-id (get keywords keyword-id))
                         label (and kw (d/project-label (:label-id kw)))
                         label-value (and kw (:label-value kw))
                         class (cond
                                 (nil? kw)
                                 ""
                                 (= (:category kw) "include")
                                 (str "ui keyword include-label green basic " label-class) 
                                 (= (:category kw) "exclude")
                                 (str "ui keyword exclude-label orange basic " label-class)
                                 :else
                                 "")
                         span-content
                         (dangerous
                          :span {:class class
                                 :on-click
                                 (when (and (d/editing-article-labels?)
                                            kw label label-value)
                                   (fn []
                                     (enable-label-value
                                      article-id (:label-id label) label-value)))}
                          text)]
                     (if (and kw show-tooltip (d/editing-article-labels?))
                       [[with-tooltip span-content
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
                            [:div.ui.row.label-name
                             (str (:name label))]
                            [:div.ui.row.label-separator]
                            [:div.ui.row.label-value
                             (str label-value)]]]]]]
                       [span-content]))))
           (apply concat)
           vec)))))

(defn render-abstract [article-id]
  (let [sections (d/data [:articles article-id :abstract-render])]
    [:div
     (doall
      (->> sections
           (map-indexed
            (fn [idx {:keys [name content]}]
              (if name
                ^{:key {:abstract-section [article-id idx]}}
                [:div
                 [:strong (-> name str/trim str/capitalize)]
                 ": "
                 [render-keywords article-id content]]
                ^{:key {:abstract-section [article-id idx]}}
                [:div
                 [render-keywords article-id content]])))))]))

(defn label-values-component [article-id user-id]
  (fn [article-id & [user-id]]
    (let [labels (d/project-labels-ordered)
          values (d/user-label-values article-id user-id)
          values (if-not (empty? values)
                   values
                   (d/get-article-labels article-id user-id))]
      [:div {:style {:margin-top "-8px"
                     :margin-bottom "-9px"
                     :margin-left "-6px"
                     :margin-right "-6px"}}
       (doall
        (->>
         labels
         (map :label-id)
         (map #(do [% (get values %)]))
         (map-indexed
          (fn [i [label-id answer]]
            (let [label-name
                  (d/project [:labels label-id :name])
                  answer-str (if (nil? answer)
                               "unknown"
                               (str answer))]
              ^{:key {:label-value (str i "__" article-id)}}
              [label-answer-tag label-id answer])))))])))

(defn article-docs-component [article-id]
  (let [docs (d/article-documents article-id)]
    [:div.ui.two.column.grid
     (doall
      (->>
       docs
       (map (fn [{:keys [document-id file-name]}]
              ^{:key {:document-link [article-id document-id file-name]}}
              [:div.ui.column
               [:a.ui.fluid.labeled.button
                {:target "_blank"
                 :href (d/article-document-url
                        document-id file-name)}
                [:div.ui.green.button
                 {:style {:min-width "70px"
                          :box-sizing "content-box"}}
                 [:i.file.icon]
                 "Open"]
                [:div.ui.fluid.label
                 (str file-name)]]]))))]))

(defn article-short-info-component
  "Shows a minimal summary of an article with a representation of its match
  quality and how it has been manually classified.
  `article-id` is required to specify the article.
  `show-labels` is a boolean (default false) specifying whether to display
  user values for labels on the article.
  `user-id` is optional, if specified then only input from that user will
  be included."
  [article-id & [show-labels user-id]]
  (fn [article-id & [show-labels user-id]]
    (when-let [article (get-in @state [:data :articles article-id])]
      (let [similarity (:score article)
            show-similarity?
            (and similarity
                 (d/project [:member-labels
                             (s/current-user-id)
                             :confirmed article-id])
                 (some->>
                  (d/project [:stats :predict
                              (d/project :overall-label-id)
                              :counts :labeled])
                  (not= 0)))
            docs (d/article-documents article-id)]
        [:div.ui.segments
         (when show-similarity?
           [:div.ui.top.attached.segment
            [similarity-bar similarity]])
         [:div.ui
          {:class (if show-similarity?
                    "attached segment"
                    "top attached segment")}
          [:h3.header
           [:a.ui.link {:href (str "/article/" article-id)}
            (:primary-title article)]
           (when-let [journal-name (:secondary-title article)]
             (str  " - " journal-name))]
          (when-not (empty? (:authors article))
            [:h5.header {:style {:margin-top "0px"}}
             (truncated-horizontal-list 5 (:authors article))])]
         (when (not (empty? docs))
           [:div.ui.attached.segment
            [:div {:style {:padding-top "1rem"}}
             [article-docs-component article-id]]])
         [:div.ui.bottom.attached.segment
          (when (and show-labels
                     ((comp not empty?)
                      (d/user-label-values article-id user-id)))
            [label-values-component article-id user-id])]]))))

(defn article-info-component
  "Shows an article with a representation of its match quality and how it
  has been manually classified.
  `article-id` is required to specify the article.
  `show-labels` is a boolean (default false) specifying whether to display
  user values for labels on the article.
  `user-id` is optional, if specified then only input from that user will
  be included."
  [article-id & [show-labels user-id review-status classify?]]
  (fn [article-id & [show-labels user-id]]
    (when-let [article (get-in @state [:data :articles article-id])]
      (let [keywords (d/project-keywords)
            similarity (:score article)
            show-similarity?
            (and similarity
                 (d/project [:member-labels
                             (s/current-user-id)
                             :confirmed article-id])
                 (some->>
                  (d/project [:stats :predict
                              (d/project :overall-label-id)
                              :counts :labeled])
                  (not= 0)))
            percent (Math/round (* 100 similarity))
            all-labels (d/get-article-labels article-id)
            labels (and show-labels
                        user-id
                        (d/user-label-values article-id user-id))
            have-labels? (if labels true false)
            docs (d/article-documents article-id)]
        [:div
         [:div.ui.top.attached.header.segment.middle.aligned
          [:div.ui
           {:style {:float "left"}}
           [:h3 "Article info"]]
          (when review-status
            (let [sstr
                  (cond (= review-status "conflict")
                        "Resolving conflict in user labels"
                        (= review-status "single")
                        "Reviewed by one other user"
                        (= review-status "fresh")
                        "Not yet reviewed"
                        :else nil)
                  color
                  (cond (= review-status "conflict") "purple"
                        :else "")]
              (when sstr
                [:div {:style {:float "right"}}
                 [:div.ui.large.label
                  {:class color
                   :style {:margin-top "-3px"
                           :margin-bottom "-3px"
                           :margin-right "0px"}}
                  (str sstr)]])))
          [:div {:style {:clear "both"}}]]
         (when (and classify?
                    (= review-status "conflict")
                    (not (empty? all-labels))
                    (not (= (keys all-labels) [user-id])))
           (doall
            (for [label-user-id (keys all-labels)]
              (when (and
                     (not= label-user-id user-id)
                     (not (empty?
                           (->> (get all-labels label-user-id)
                                vals
                                (filter (comp not nil?))))))
                ^{:key {:classify-existing-labels [article-id label-user-id]}}
                [:div.ui.attached.segment.middle.aligned
                 [:h3
                  {:style {:margin-bottom "7px"}}
                  [:a
                   {:href (str "/user/" label-user-id)}
                   (str (-> label-user-id d/project-user-info :user :email))]
                  " saved labels"]
                 [label-values-component article-id label-user-id true]]))))
         (when show-similarity?
           [:div.ui.attached.segment
            [similarity-bar similarity]])
         [:div.ui
          {:class (if (and show-labels have-labels?)
                    "attached segment"
                    "bottom attached segment")}
          [:div.content
           [:h3.header
            [render-keywords
             article-id
             (d/data [:articles article-id :title-render])
             {:label-class "large button"}]]
           (when-not (empty? (:secondary-title article))
             [:h3.header {:style {:margin-top "0px"}}
              [render-keywords
               article-id
               (d/data [:articles article-id :journal-render])
               {:label-class "large button"}]])
           (when-not (empty? (:authors article))
             [:h5.header {:style {:margin-top "0px"}}
              (truncated-horizontal-list 5 (:authors article))])
           (when (not (empty? (:abstract article)))
             [render-abstract article-id])
           (when (not (empty? docs))
             [:div {:style {:padding-top "1rem"}}
              [article-docs-component article-id]])
           (let [urls
                 (concat (-> article :urls)
                         (-> article :locations d/article-location-urls))]
             [:div.content.ui.list
              (->> urls
                   (map-indexed
                    (fn [idx url]
                      ^{:key {:article-url {:aid article-id
                                            :url-idx idx}}}
                      [out-link url]))
                   doall)])]]
         (when (and show-labels have-labels?)
           [:div.ui.bottom.attached.segment
            [:div.content
             [label-values-component article-id user-id]]])]))))

(defn label-editor-component
  "UI component for editing label values on an article.

  `article-id` is the article being edited.

  `labels-path` is a sequence of keys specifying the path
  in `state` where the label values set by the user will be stored."
  [article-id labels-path label-values]
  (let [labels (d/project :labels)
        ordered-label-ids (->> (d/project-labels-ordered)
                               (map :label-id))
        core-ids (->> ordered-label-ids
                      (filter #(= "inclusion criteria"
                                  (-> (get labels %) :category))))
        extra-ids (->> ordered-label-ids
                       (remove #(= "inclusion criteria"
                                   (-> (get labels %) :category))))
        make-inclusion-tag
        (fn [label-id]
          (when (= "inclusion criteria"
                   (:category (get labels label-id)))
            (let [current-answer (get label-values label-id)
                  inclusion (d/label-answer-inclusion
                             label-id current-answer)
                  color (case inclusion
                          true "green"
                          false "orange"
                          nil "")
                  iclass (case inclusion
                           true "plus circle icon"
                           false "minus circle icon"
                           nil "help circle icon")]
              [:div.ui.left.corner.label
               {:class (str color)}
               [:i {:class (str iclass)}]])))
        make-boolean-column
        (fn [label-id]
          (let [label (get labels label-id)]
            ^{:key {:article-label label-id}}
            [:div.ui.column
             {:style {:background-color
                      (if (:required label)
                        "rgba(215,215,215,1)"
                        nil)
                      :padding "0px"}}
             [:div.ui.middle.aligned.grid
              {:style {:margin "0px"}}
              [with-tooltip
               [:div.ui.row
                {:style {:padding-bottom "8px"
                         :padding-top "12px"
                         :text-align "center"}}
                (make-inclusion-tag label-id)
                [:span
                 {:style {:width "100%"}}
                 (str (:short-label label) "?")]]]
              [:div.ui.inverted.popup.top.left.transition.hidden
               (:question label)]
              [:div.ui.row
               {:style {:padding-top "0px"
                        :padding-bottom "18px"}}
               [:div
                {:style {:margin-left "auto"
                         :margin-right "auto"}}
                [three-state-selection
                 (fn [new-value]
                   (swap! state assoc-in
                          (concat labels-path [label-id])
                          new-value)
                   (ajax/send-labels
                    article-id
                    (d/active-label-values article-id labels-path)))
                 (get label-values label-id)]]]]]))
        make-categorical-column
        (fn [label-id]
          (let [label (get labels label-id)]
            ^{:key {:article-label label-id}}
            [:div.ui.column
             {:style {:background-color
                      (if (:required label)
                        "rgba(215,215,215,1)"
                        nil)
                      :padding "0px"}}
             [:div.ui.middle.aligned.grid
              {:style {:margin "0px"}}
              [with-tooltip
               [:div.ui.row
                {:style {:padding-bottom "8px"
                         :padding-top "12px"
                         :text-align "center"}}
                (make-inclusion-tag label-id)
                [:span
                 {:style {:width "100%"}}
                 (:short-label label)]]]
              [:div.ui.inverted.popup.top.left.transition.hidden
               (:question label)]
              [:div.ui.row
               {:style {:padding-top "8px"
                        :padding-bottom "10px"}}
               (let [current-values
                     (get label-values label-id)]
                 [multi-choice-selection
                  label-id
                  (-> label :definition :all-values)
                  current-values
                  (fn [v t]
                    (swap! state assoc-in
                           (concat labels-path [label-id])
                           (-> (conj (get
                                      (d/active-label-values article-id labels-path)
                                      label-id)
                                     v)
                               distinct vec))
                    (ajax/send-labels
                     article-id
                     (d/active-label-values article-id labels-path)))
                  (fn [v t]
                    (swap! state assoc-in
                           (concat labels-path [label-id])
                           (remove
                            (partial = v)
                            (get
                             (d/active-label-values article-id labels-path)
                             label-id)))
                    (ajax/send-labels
                     article-id
                     (d/active-label-values article-id labels-path)))])]]]))
        make-column
        (fn [label-id]
          (let [{:keys [value-type]
                 :as label} (get labels label-id)]
            (case value-type
              "boolean" (make-boolean-column label-id)
              "categorical" (make-categorical-column label-id)
              nil)))
        make-label-columns
        (fn [label-ids]
          (doall (map make-column label-ids)))]
    [:div.ui.segments
     [:div.ui.top.attached.header
      [:h3
       "Edit labels "
       [with-tooltip
        [:a {:href "/labels"}
         [:i.medium.yellow.info.circle.icon]]]
       [:div.ui.inverted.popup.top.left.transition.hidden
        "View label definitions"]]]
     [:div.ui.attached.segment.label-section-header
      [:h4 "Inclusion criteria"]]
     [:div.ui.attached.grid.segment
      {:class (cond (full-size?) "four column"
                    (mobile?) "two column"
                    :else "three column")
       :style {:padding "0px"}}
      (make-label-columns core-ids)]
     [:div.ui.attached.segment.label-section-header
      [:h4 "Extra labels"]]
     [:div.ui.grid.segment
      {:class (str (cond (full-size?) "four column"
                         (mobile?) "two column"
                         :else "three column")
                   " "
                   (if true ;; test if project has notes inputs below this
                     "bottom attached"
                     "attached"))
       :style {:padding "0px"}}
      (make-label-columns extra-ids)]]))

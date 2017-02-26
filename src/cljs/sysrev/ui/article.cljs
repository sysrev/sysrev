(ns sysrev.ui.article
  (:require
   [clojure.core.reducers :refer [fold]]
   [clojure.string :as str]
   [sysrev.base :refer [st work-state]]
   [sysrev.state.core :as st :refer [data]]
   [sysrev.state.project :as project :refer [project]]
   [sysrev.state.labels :as l]
   [sysrev.state.notes :as notes]
   [sysrev.shared.util :refer [map-values re-pos]]
   [sysrev.shared.keywords :refer [process-keywords format-abstract]]
   [sysrev.ui.components :refer
    [similarity-bar truncated-horizontal-list out-link label-answer-tag
     with-tooltip three-state-selection dangerous
     inconsistent-answers-notice]]
   [sysrev.util :refer [full-size? mobile? in?]]
   [sysrev.ajax :as ajax]
   [reagent.core :as r]
   [sysrev.state.labels :as labels])
  (:require-macros [sysrev.macros :refer [with-mount-hook using-work-state]]))

(defn enable-label-value
  "This is a top-level function for the editing interface that ensures that
  the UI components and internal state are both updated.

  Modifies the user input value of `label-id` for `article-id` which is 
  currently being edited."
  [label-id label-value]
  (using-work-state
   (let [value-type (project :labels label-id :value-type)
         article-id (l/active-editor-article-id)]
     (cond (= value-type "boolean")
           (do (l/set-label-value label-id label-value)
               (ajax/send-active-labels))
           (= value-type "categorical")
           (.dropdown
            (js/$ (str "#label-edit-" article-id "-" label-id))
            "set selected"
            label-value)))))

(defn label-help-popup-element [label-id]
  (when-let [{:keys [category required question definition] :as label}
             (project :labels label-id)]
    [:div.ui.inverted.grid.popup.transition.hidden.label-help
     [:div.middle.aligned.center.aligned.row.label-help-header
      [:div.ui.sixteen.wide.column
       (case category
         "inclusion criteria"
         (if required
           [:span "Inclusion criteria [Required]"]
           [:span "Inclusion criteria"])
         [:span "Extra label"])]]
     [:div.middle.aligned.center.aligned.row.label-help-question
      [:div.sixteen.wide.column.label-help
       [:div [:span (str question)]]
       (when-let [examples (:examples definition)]
         [:div
          [:div.ui.small.divider]
          [:div
           [:strong "Examples: "]
           (doall
            (map-indexed
             (fn [i ex]
               ^{:key {:label-popup-example [label-id i]}}
               [:div.ui.small.green.label (str ex)])
             examples))]])]]]))

(defn keyword-button-elements [content label-name label-value]
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

(defn render-keywords
  [article-id content & [{:keys [label-class show-tooltip]
                          :or {label-class "small button"
                               show-tooltip true}}]]
  (let [keywords (project :keywords)]
    (vec
     (concat
      [:span]
      (->> content
           (mapv (fn [{:keys [keyword-id text]}]
                   (let [kw (and keyword-id (get keywords keyword-id))
                         label (and kw (project :labels (:label-id kw)))
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
                          :span
                          {:class class
                           :on-click
                           (when (and kw label label-value
                                      (l/editing-article-labels?))
                             #(enable-label-value
                               (:label-id label) label-value))}
                          text)]
                     (if (and kw show-tooltip (l/editing-article-labels?))
                       (keyword-button-elements
                        span-content (:name label) label-value)
                       [span-content]))))
           (apply concat)
           vec)))))

(defn render-abstract [article-id]
  (let [sections (data [:articles article-id :abstract-render])]
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
    (let [labels (l/project-labels-ordered)
          values (l/user-label-values article-id user-id)]
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
            (let [label-name (project :labels label-id :name)
                  answer-str (if (nil? answer)
                               "unknown"
                               (str answer))]
              ^{:key {:label-value (str i "__" article-id)}}
              [label-answer-tag label-id answer])))))])))

(defn article-docs-component [article-id]
  (let [docs (project/article-documents article-id)]
    [:div.ui.two.column.grid
     (doall
      (->>
       docs
       (map (fn [{:keys [document-id file-name]}]
              ^{:key {:document-link [article-id document-id file-name]}}
              [:div.ui.column
               [:a.ui.fluid.labeled.button
                {:target "_blank"
                 :href (project/article-document-url document-id file-name)}
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
    (when-let [article (data [:articles article-id])]
      (let [similarity (:score article)
            show-similarity?
            (and similarity
                 (project :member-labels (st/current-user-id)
                          :confirmed article-id)
                 (some->>
                  (project :stats :predict
                           (project :overall-label-id)
                           :counts :labeled)
                  (not= 0)))
            docs (project/article-documents article-id)
            unote (and user-id (notes/get-note-field article-id user-id "default"))
            note-content (and unote (:active unote))]
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
         [:div.ui.segment
          {:class (if (empty? note-content) "bottom attached" "attached")}
          (when (and show-labels
                     ((comp not empty?)
                      (l/user-label-values article-id user-id)))
            [label-values-component article-id user-id])]
         (when-not (empty? note-content)
           [:div.ui.bottom.attached.segment
            {:style {:padding-top "0.2em"
                     :padding-bottom "0.2em"
                     :padding-left "0.5em"
                     :padding-right "0.5em"}}
            [:div.ui.labeled.button
             {:style {:margin-left "4px"
                      :margin-right "4px"
                      :margin-top "3px"
                      :margin-bottom "4px"}}
             [:div.ui.small.blue.button.flex-center-children
              [:div "Notes"]]
             [:div.ui.basic.label
              {:style {:text-align "justify"}}
              note-content]]])]))))

(defn article-info-component
  "Shows an article with a representation of its match quality and how it
  has been manually classified.
  `article-id` is required to specify the article.
  `show-labels` is a boolean (default false) specifying whether to display
  user values for labels on the article.
  `user-id` is optional, if specified then only input from that user will
  be included."
  [article-id & [show-labels user-id review-status classify?]]
  (fn [article-id & [show-labels user-id review-status classify?]]
    (when-let [article (data [:articles article-id])]
      (let [unote (and user-id (notes/get-note-field
                                article-id user-id "default"))
            note-content (and unote (:active unote))
            keywords (project :keywords)
            similarity (:score article)
            show-similarity?
            (and similarity
                 (project :member-labels
                          (st/current-user-id)
                          :confirmed article-id)
                 (some->>
                  (project :stats :predict
                           (project :overall-label-id)
                           :counts :labeled)
                  (not= 0)))
            percent (Math/round (* 100 similarity))
            all-labels (l/article-label-values article-id)
            labels (and show-labels
                        user-id
                        (l/user-label-values article-id user-id))
            have-labels? (if labels true false)
            docs (project/article-documents article-id)]
        [:div
         [:div.ui.top.attached.header.segment.middle.aligned.article-info-header
          [:div.ui
           {:style {:float "left"}}
           [:h4 "Article info"]]
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
                 [:div.ui.basic.label
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
                   (str (project/project-user label-user-id :user :email))]
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
             (data [:articles article-id :title-render])
             {:label-class "large button"}]]
           (when-not (empty? (:secondary-title article))
             [:h3.header {:style {:margin-top "0px"}}
              [render-keywords
               article-id
               (data [:articles article-id :journal-render])
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
                         (-> article :locations project/article-location-urls))]
             [:div.content.ui.list
              (->> urls
                   (map-indexed
                    (fn [idx url]
                      ^{:key {:article-url {:aid article-id
                                            :url-idx idx}}}
                      [out-link url]))
                   doall)])]]
         (when (and show-labels have-labels?)
           [:div.ui.segment
            {:class (if (empty? note-content)
                      "bottom attached" "attached")}
            [:div.content
             [label-values-component article-id user-id]]])
         (when (and show-labels ((comp not empty?) note-content))
           [:div.ui.bottom.attached.segment
            {:style {:padding "0.5em"}}
            [:div.ui.labeled.button
             {:style {:margin-left "4px"
                      :margin-right "4px"
                      :margin-top "3px"
                      :margin-bottom "4px"}}
             [:div.ui.small.blue.button.flex-center-children
              [:div "Notes"]]
             [:div.ui.basic.label
              {:style {:text-align "justify"}}
              note-content]]])]))))

(defn note-input-element [article-id]
  (let [user-id (st/current-user-id)
        pnote (project :notes :default)
        anote (notes/get-note-field
               article-id user-id (:name pnote))]
    (when pnote
      [:div.ui.segment.notes
       {:class (if true "bottom attached" "attached")}
       [:div.ui.middle.aligned.form.notes
        [:div.middle.aligned.field.notes
         [:label.middle.aligned.notes
          (:description pnote)
          [:i.large.middle.aligned.icon
           {:class
            (cond
              (not (notes/note-field-synced? article-id (:name pnote)))
              "blue asterisk loading"
              (empty? (:active anote))
              "grey write"
              :else
              "green check circle outline")}]]
         [:textarea
          {:type "text"
           :rows 2
           :name (:name pnote)
           :value (or (:active anote) "")
           :on-change
           #(using-work-state
             (swap! work-state
                    (notes/update-note-field
                     article-id (:name pnote)
                     (-> % .-target .-value))))}]]]])))

(defn string-label-element [label-id]
  (let [label (project :labels label-id)
        curvals (as-> (l/active-label-values nil label-id) vs
                  (if (empty? vs) [""] vs))]
    [:div.inner {:style {:width "100%"}}
     (doall
      (->>
       curvals
       (map-indexed
        (fn [i val]
          (let [left-action? true
                right-action? (and (= (+ i 1) (count curvals))
                                   (-> label :definition :multi?))]
            ^{:key {:string-label [label-id i]}}
            [:div.ui.form.string-label
             [:div.field.string-label
              {:class (cond
                        (empty? val)
                        ""
                        (l/string-label-valid? label-id val)
                        "success"
                        :else "error")}
              [:div.ui.fluid.small
               {:class
                (str
                 (if left-action? "labeled" "")
                 " " (if right-action? "right action input" "input")
                 " " )}
               (when left-action?
                 [:div.ui.label.input-remove
                  [:div.ui.button
                   {:class (if (and (= i 0)
                                    (= (count curvals) 1)
                                    (empty? val)) "disabled" "")
                    :on-click
                    (fn [ev]
                      (l/update-label-value
                       label-id #(->> (assoc (vec %) i "")
                                      (filterv not-empty))))}
                   [:i.fitted.small.remove.icon]]])
               [:input
                {:type "text"
                 :name (str label-id "__" i)
                 :value val
                 :on-change
                 (fn [ev]
                   (let [s (-> ev .-target .-value)]
                     (l/update-label-value
                      label-id #(assoc (vec %) i s))))}]
               (when right-action?
                 [:div.ui.icon.button.input-row
                  {:class (if (empty? val) "disabled" "")
                   :on-click
                   (fn [ev]
                     (l/update-label-value
                      label-id #(assoc (vec %) (count curvals) "")))}
                  [:i.fitted.small.plus.icon]])]]])))))]))

#_
(defn numeric-label-element [label-id]
  (let [label (project :labels label-id)
        curvals (as-> (l/active-label-values nil label-id) vs
                  (if (empty? vs) [""] vs))]
    [:div.inner {:style {:width "100%"}}
     (doall
      (->>
       curvals
       (map-indexed
        (fn [i val]
          (let [left-action? true
                right-action? (and (= (+ i 1) (count curvals))
                                   (-> label :definition :multi?))]
            ^{:key {:string-label [label-id i]}}
            [:div.ui.form.string-label
             [:div.field.string-label
              {:class (cond
                        (empty? val)
                        ""
                        (l/string-label-valid? label-id val)
                        "success"
                        :else "error")}
              [:div.ui.fluid.small
               {:class
                (str
                 (if left-action? "labeled" "")
                 " " (if right-action? "right action input" "input")
                 " " )}
               (when left-action?
                 [:div.ui.label.input-remove
                  [:div.ui.button
                   {:class (if (and (= i 0)
                                    (= (count curvals) 1)
                                    (empty? val)) "disabled" "")
                    :on-click
                    (fn [ev]
                      (l/update-label-value
                       label-id #(->> (assoc (vec %) i "")
                                      (filterv not-empty))))}
                   [:i.fitted.small.remove.icon]]])
               [:input
                {:type "text"
                 :name (str label-id "__" i)
                 :value val
                 :on-change
                 (fn [ev]
                   (let [s (-> ev .-target .-value)]
                     (l/update-label-value
                      label-id #(assoc (vec %) i s))))}]
               (when right-action?
                 [:div.ui.icon.button.input-row
                  {:class (if (empty? val) "disabled" "")
                   :on-click
                   (fn [ev]
                     (l/update-label-value
                      label-id #(assoc (vec %) (count curvals) "")))}
                  [:i.fitted.small.plus.icon]])]]])))))]))

(defn multi-choice-selection [label-id]
  (r/create-class
   {:component-did-mount
    (fn [c]
      (.dropdown
       (js/$ (r/dom-node c))
       (clj->js
        {:onAdd
         (fn [v t]
           (using-work-state
            (l/update-label-value
             label-id #(-> % vec (conj v) distinct))
            (ajax/send-active-labels)))
         :onRemove
         (fn [v t]
           (using-work-state
            (l/update-label-value
             label-id #(->> % (remove (partial = v)) vec))
            (ajax/send-active-labels)))
         :onChange
         (fn [_] (.dropdown (js/$ (r/dom-node c))
                            "hide"))})))
    :component-will-update
    (fn [c]
      (using-work-state
       (let [active-vals (->> (l/active-label-values nil label-id)
                              (str/join ","))
             comp-vals (-> (js/$ (r/dom-node c))
                           (.dropdown "get value")) ]
         (when (and (not= comp-vals active-vals))
           (-> (js/$ (r/dom-node c))
               (.dropdown "set exactly" active-vals))))))
    :reagent-render
    (fn [label-id]
      (let [all-values
            (project/project :labels label-id :definition :all-values)
            article-id (l/active-editor-article-id)
            current-values (l/active-label-values article-id label-id)
            dom-id (str "label-edit-" article-id "-" label-id)]
        [:div.ui.large.fluid.multiple.selection.dropdown
         {:id dom-id
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
          {:name (str "label-edit(" dom-id ")")
           :value (str/join "," current-values)
           :type "hidden"}]
         [:i.dropdown.icon]
         (if (project :labels label-id :required)
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

(defn label-editor-component
  "UI component for editing label values on an article."
  []
  (let [article-id (l/active-editor-article-id)
        label-values (l/active-label-values)
        pnote (project :notes :default)
        user-id (st/current-user-id)
        labels (project :labels)
        ordered-label-ids (->> (l/project-labels-ordered)
                               (map :label-id))
        core-ids (->> ordered-label-ids
                      (filter #(= "inclusion criteria"
                                  (-> (get labels %) :category))))
        extra-ids (->> ordered-label-ids
                       (remove #(= "inclusion criteria"
                                   (-> (get labels %) :category))))
        n-cols (cond (full-size?) 4 (mobile?) 2 :else 3)
        n-cols-str (case n-cols 4 "four" 2 "two" 3 "three")
        make-inclusion-tag
        (fn [label-id]
          (if (= "inclusion criteria"
                 (:category (get labels label-id)))
            (let [current-answer (get label-values label-id)
                  inclusion (l/label-answer-inclusion
                             label-id current-answer)
                  color (case inclusion
                          true "green"
                          false "orange"
                          nil "grey")
                  iclass (case inclusion
                           true "circle plus icon"
                           false "circle minus icon"
                           nil "circle outline icon")]
              [:i.left.floated.fitted {:class (str color " " iclass)}])
            [:i.left.floated.fitted {:class "grey content icon"}]))
        make-boolean-column
        (fn [label-id]
          (let [label (get labels label-id)]
            ^{:key {:article-label label-id}}
            [:div.ui.column.label-edit
             {:class (cond (:required label) "required"
                           (not= (:category label)
                                 "inclusion criteria") "extra"
                           :else "")}
             [:div.ui.middle.aligned.grid.label-edit
              [with-tooltip
               [:div.ui.row.label-edit-name
                (make-inclusion-tag label-id)
                [:span.name
                 [:span.inner
                  (str (:short-label label) "?")]]]
               {:delay {:show 400
                        :hide 0}
                :hoverable false
                :transition "fade up"
                :distanceAway 8
                :variation "basic"}]
              [label-help-popup-element label-id]
              [:div.ui.row.label-edit-value.boolean
               [:div.inner
                [three-state-selection
                 (fn [new-value]
                   (using-work-state
                    (l/set-label-value label-id new-value)
                    (ajax/send-active-labels)))
                 (get label-values label-id)]]]]]))
        make-categorical-column
        (fn [label-id]
          (let [label (get labels label-id)]
            ^{:key {:article-label label-id}}
            [:div.ui.column.label-edit
             {:class (if (:required label) "required" nil)}
             [:div.ui.middle.aligned.grid.label-edit
              [with-tooltip
               [:div.ui.row.label-edit-name
                (make-inclusion-tag label-id)
                [:span.name
                 [:span.inner
                  (:short-label label)]]]
               {:delay {:show 400
                        :hide 0}
                :hoverable false
                :transition "fade up"
                :distanceAway 8
                :variation "basic"}]
              [label-help-popup-element label-id]
              [:div.ui.row.label-edit-value.category
               [:div.inner [multi-choice-selection label-id]]]]]))
        make-string-column
        (fn [label-id]
          (let [label (get labels label-id)]
            ^{:key {:article-label label-id}}
            [:div.ui.column.label-edit
             {:class (if (:required label) "required" nil)}
             [:div.ui.middle.aligned.grid.label-edit
              [with-tooltip
               [:div.ui.row.label-edit-name
                [:span.name [:span.inner (:short-label label)]]]
               {:delay {:show 400
                        :hide 0}
                :hoverable false
                :transition "fade up"
                :distanceAway 8
                :variation "basic"}]
              [label-help-popup-element label-id]
              [:div.ui.row.label-edit-value.string
               [string-label-element label-id]]]]))
        make-column
        (fn [label-id]
          (let [{:keys [value-type]
                 :as label} (get labels label-id)]
            (case value-type
              "boolean" (make-boolean-column label-id)
              "categorical" (make-categorical-column label-id)
              "string" (make-string-column label-id)
              nil)))
        make-label-columns
        (fn [label-ids n-cols]
          (doall
           (for [row (partition-all n-cols label-ids)]
             ^{:key {:label-row (first row)}}
             [:div.row
              (doall
               (concat
                (map make-column row)
                (when (< (count row) n-cols)
                  [^{:key {:label-row-end (last row)}}
                   [:div.column]])))])))]
    [:div.ui.segments
     [:div.ui.top.attached.header
      [:h3
       "Edit labels "
       [with-tooltip
        [:a {:href "/project/labels"}
         [:i.medium.grey.help.circle.icon]]]
       [:div.ui.inverted.popup.top.left.transition.hidden
        "View label definitions"]]]
     [:div.ui.label-section
      {:class (str "attached "
                   n-cols-str " column "
                   "celled grid segment")}
      (make-label-columns (concat core-ids extra-ids) n-cols)]
     [inconsistent-answers-notice label-values]
     [note-input-element article-id]]))

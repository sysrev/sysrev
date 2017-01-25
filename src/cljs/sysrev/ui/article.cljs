(ns sysrev.ui.article
  (:require
   [clojure.core.reducers :refer [fold]]
   [clojure.string :as str]
   [sysrev.base :refer [state]]
   [sysrev.state.core :as s]
   [sysrev.state.data :as d]
   [sysrev.ui.components :refer
    [similarity-bar truncated-horizontal-list out-link label-answer-tag
     with-tooltip three-state-selection multi-choice-selection dangerous]]
   [sysrev.util :refer [re-pos map-values full-size? in?]]
   [sysrev.ajax :as ajax]
   [reagent.core :as r]))

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

;; First pass over text, just breaks apart into groups of "Groupname: grouptext"
(defn- sections' [text]
  (let [group-header #"(^|\s)([A-Za-z][A-Za-z /]+):"
        groups (re-pos group-header text)
        ;; remove the colon:
        headers (map-values #(. % (slice 0 -1)) (sorted-map) groups)
        windows (partition 2 1 headers)
        start-stop-names (map (fn [[[start name] [end _]]]
                                [(+ (count name) start 1) end name])
                              windows)
        secs (->> start-stop-names
                  (map (fn [[start stop secname]]
                         [secname (.. text (slice start stop) (trim))])))
        start-index (-> groups first first)]
    (cond
      (= start-index 0) (into [""] secs)
      (nil? start-index) [text]
      :else (into [(. text (slice 0 start-index))] secs))))

(defn sections
  "Take a blob of text, and look for sections such as \"Background: Text..\"
  Split this text into such sections.
  Return in the form: [preamble [secname sectext] [secname sectext] ...].
  If no sections are found, all the text will end up in the preamble."
  [text]
  (let [secs (sections' text)]
    (into
     [(first secs)]
     (->> (vec (rest secs))
          (fold
           (fn [acc [secname sec]]
             (let [[lastsecname lastsec] (last acc)]
               (cond
                 (nil? secname) (vec acc)
                 :else
                 (cond
                   (nil? lastsecname) [[secname sec]]
                   ;; Here, require the previous section ended with a period,
                   ;; to start a new section.
                   ;; Split was correct. continue.
                   (= (last lastsec) ".")
                   (conj (vec acc) [secname sec])
                   ;; Require the last section had text.
                   ;; Otherwise merge section titles.
                   (empty? lastsec)
                   (conj (vec (butlast acc))
                         [(str lastsecname " " secname) sec])
                   ;; Split was incorrect, undo split.
                   :else (conj (vec (butlast acc))
                               [lastsecname (str lastsec ": " sec)]))))))))))

(defn sections-to-text
  "Take a result from `sections` function and return something close to the
  original abstract text."
  [sections]
  (->> sections
       (map (fn [s]
              (if (string? s)
                s
                (str/join ": " s))))
       (str/join " ")))

(defn strip-symbols [s]
  (reduce (fn [result sym]
            (str/replace result sym ""))
          s
          ["(" ")" "[" "]" "." "," "!"]))

(defn canonical-keyword [s]
  (-> s strip-symbols str/lower-case))

(defn toks-match-count [keyword-toks next-toks]
  (let [match-toks
        (->> next-toks
             (map-indexed
              (fn [i tok]
                (->> (str/split (:canon tok) #"\-")
                     (map (fn [subtok]
                            {:idx i :str subtok})))))
             (apply concat)
             (take (count keyword-toks)))]
    (when (= keyword-toks
             (map :str match-toks))
      (->> match-toks last :idx (+ 1)))))

(defn match-toks-keyword [kmatch-vals next-toks]
  (let [kw
        (->> kmatch-vals
             (map
              (fn [{kw-toks :toks :as kw}]
                (assoc kw :match-count
                       (toks-match-count kw-toks next-toks))))
             (filter (comp not nil? :match-count))
             (map #(select-keys % [:keyword-id :toks :match-count]))
             first)]
    (if (nil? kw)
      nil
      {:keyword-id (:keyword-id kw)
       :keyword-toks (take (:match-count kw) next-toks)
       :rest-toks (drop (:match-count kw) next-toks)})))

(defn split-abstract-elts [kmatch-vals result pending rest-toks]
  (if (empty? rest-toks)
    (if (empty? pending)
      result
      (concat result [{:keyword-id nil :toks pending}]))
    (let [kw-match (match-toks-keyword kmatch-vals rest-toks)]
      (if (nil? kw-match)
        (split-abstract-elts kmatch-vals
                             result
                             (conj pending (first rest-toks))
                             (rest rest-toks))
        (split-abstract-elts kmatch-vals
                             (concat result
                                     [{:keyword-id nil
                                       :toks pending}]
                                     [{:keyword-id (:keyword-id kw-match)
                                       :toks (:keyword-toks kw-match)}])
                             []
                             (:rest-toks kw-match))))))

(defn tokenize-article-text [text]
  (->> (str/split text #" ")
       (mapv (fn [s]
               {:raw s
                :canon (canonical-keyword s)}))))

(defn render-abstract-keywords
  "Takes as input a string of content from an abstract (possibly contains
   HTML from the original abstract text), and returns a [:span] element containing
   the text content with any keyword terms wrapped in elements to provide
   highlighting."
  [article-id text & [{:keys [label-class show-tooltip]
                       :or {label-class "small button"
                            show-tooltip true}}]]
  (let [keywords (d/project-keywords)
        kmatch-vals (->> (vals (d/project-keywords))
                         (map #(select-keys % [:keyword-id :toks]))
                         (map #(assoc % :n-toks (count (:toks %))))
                         (sort-by :n-toks >))
        toks (tokenize-article-text text)]
    (vec
     (concat
      [:span]
      (->> (split-abstract-elts kmatch-vals [] [] toks)
           (mapv (fn [tgroup]
                   (let [kw (and (:keyword-id tgroup)
                                 (get keywords (:keyword-id tgroup)))
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
                         content
                         (->> (:toks tgroup)
                              (map :raw)
                              (str/join " ")
                              (dangerous
                               :span
                               {:class class
                                :on-click
                                (when (and (d/editing-article-labels?)
                                           kw label label-value)
                                  (fn []
                                    (enable-label-value
                                     article-id (:label-id label) label-value)))}))]
                     (if (and kw show-tooltip (d/editing-article-labels?))
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
                            [:div.ui.row.label-name
                             (str (:name label))]
                            [:div.ui.row.label-separator]
                            [:div.ui.row.label-value
                             (str label-value)]]]]]]
                       [content]))))
           (apply concat)
           vec)))))

(defn render-abstract [article-id]
  (let [cached (d/data [:abstract-renders article-id])]
    (if cached
      cached
      (let [text (d/data [:articles article-id :abstract])
            unformatted? (nil? (str/index-of text "\n"))
            secs (and unformatted? (sections text))
            secs-text (and secs (sections-to-text secs))
            result
            (cond
              ;; use section splitting code if existing text has no linebreaks,
              ;; and the result isn't losing content
              (and unformatted?
                   (>= (count secs-text) (* (count text) 0.9)))
              [:div
               [:div (render-abstract-keywords article-id (first secs))]
               (doall
                (->> (rest secs)
                     (map-indexed
                      (fn [idx [name text]]
                        ^{:key {:abstract-section {:name name :idx idx}}}
                        [:div
                         [:strong (-> name str/trim str/capitalize)]
                         ": "
                         (render-abstract-keywords article-id text)]))))]
              :else
              ;; otherwise show the text using existing linebreaks for formatting
              (let [secs (str/split text #"\n")]
                [:div
                 (doall
                  (->> secs
                       (map-indexed
                        (fn [idx stext]
                          ^{:key {:abstract-section idx}}
                          [:div (render-abstract-keywords article-id stext)]))))]))]
        (swap! state assoc-in
               [:data :abstract-renders article-id] result)
        result))))

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
            (render-abstract-keywords
             article-id (:primary-title article)
             {:label-class "large button"})]
           (when-not (empty? (:secondary-title article))
             [:h3.header {:style {:margin-top "0px"}}
              (render-abstract-keywords
               article-id (:secondary-title article)
               {:label-class "large button"})])
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
      {:class (if (full-size?)
                "four column"
                "three column")
       :style {:padding "0px"}}
      (make-label-columns core-ids)]
     [:div.ui.attached.segment.label-section-header
      [:h4 "Extra labels"]]
     [:div.ui.grid.segment
      {:class (str (if (full-size?)
                     "four column"
                     "three column")
                   " "
                   (if true ;; test if project has notes inputs below this
                     "bottom attached"
                     "attached"))
       :style {:padding "0px"}}
      (make-label-columns extra-ids)]]))

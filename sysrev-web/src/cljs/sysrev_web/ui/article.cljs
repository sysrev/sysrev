(ns sysrev-web.ui.article
  (:require
   [clojure.core.reducers :refer [fold]]
   [clojure.string :refer [capitalize]]
   [sysrev-web.base :refer [state]]
   [sysrev-web.state.data :as d :refer [data]]
   [sysrev-web.ui.components :refer
    [similarity-bar truncated-horizontal-list out-link label-value-tag
     with-tooltip three-state-selection]]
   [sysrev-web.util :refer [re-pos map-values full-size?]]
   [sysrev-web.ajax :as ajax]))

(defn label-values-component [article-id user-id]
  (fn [article-id & [user-id]]
    (let [cids (-> @state :data :criteria keys sort)
          values (d/user-label-values article-id user-id)
          values (if-not (empty? values)
                   values
                   (d/get-article-labels article-id user-id))]
      [:div.ui.content
       [:div.ui.horizontal.divided.list
        (doall
         (->>
          cids
          (map #(do [% (get values %)]))
          (map
           (fn [[cid answer]]
             (let [criteria-name
                   (get-in @state [:data :criteria cid :name])
                   answer-str (if (nil? answer)
                                "unknown"
                                (str answer))]
               ^{:key {:label-value (str cid "-" article-id)}}
               [:div.item.label-tag-list
                [:div.content
                 [label-value-tag cid answer]]])))))]])))

;; First pass over text, just breaks apart into groups of "Groupname: grouptext"
(defn- sections' [text]
  (let [group-header #"([A-Z][ A-Za-z]+):"
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

(defn abstract [text]
  (fn [text]
    (let [secs (sections text)]
      [:div
       [:p (first secs)]
       (->> (rest secs)
            (map-indexed
             (fn [idx [name text]]
               ^{:key {:abstract-section {:name name :idx idx}}}
               [:p
                [:strong (capitalize name)]
                ": "
                [:span text]])))])))

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
            docs (d/article-documents article-id)]
        [:div.ui.segments
         [:div.ui.top.attached.segment
          [similarity-bar similarity]]
         [:div.ui.attached.segment
          [:h3.header
           [:a.ui.link {:href (str "/article/" article-id)}
            (:primary-title article)]
           (when-let [journal-name (:secondary-title article)]
             (str  " - " journal-name))]
          (when-not (empty? (:authors article))
            [:p (truncated-horizontal-list 5 (:authors article))])]
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
                        :else "grey")]
              (when sstr
                [:div.ui.large.label
                 {:class color
                  :style {:float "right"
                          :margin-top "-3px"
                          :margin-bottom "-3px"
                          :margin-right "0px"}}
                 (str sstr)])))
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
         (when-not classify?
           (when-not (nil? similarity)
             [:div.ui.attached.segment
              [similarity-bar similarity]]))
         [:div.ui
          {:class (if (and show-labels have-labels?)
                    "attached segment"
                    "bottom attached segment")}
          [:div.content
           [:h3.header (:primary-title article)]
           (when-not (empty? (:secondary-title article))
             [:h3.header {:style {:margin-top "0px"}}
              (:secondary-title article)])
           (when-not (empty? (:authors article))
             [:p (truncated-horizontal-list 5 (:authors article))])
           (when (not (empty? (:abstract article)))
             [abstract (:abstract article)])
           (when (not (empty? docs))
             [:div {:style {:padding-top "1rem"}}
              [article-docs-component article-id]])
           [:div.content.ui.list
            (->> article :urls
                 (map-indexed
                  (fn [idx url]
                    ^{:key {:article-url {:aid article-id
                                          :url-idx idx}}}
                    [out-link url]))
                 doall)]]]
         (when (and show-labels have-labels?)
           [:div.ui.bottom.attached.segment
            [:div.content
             [label-values-component article-id user-id]]])]))))

(defn label-editor-component
  "UI component for editing label values on an article.

  `article-id` is the article being edited.

  `labels-path` is a sequence of keys specifying the path
  in `state` where the label values set by the user will be stored."
  [article-id labels-path]
  (let [criteria (data :criteria)
        label-values (d/active-label-values article-id labels-path)]
    [:div.ui.segments
     [:div.ui.top.attached.header
      [:h3
       "Edit labels "
       [with-tooltip
        [:a
         {:href "/labels"
          :data-content "View label definitions"
          :data-position "top left"}
         [:i.large.yellow.help.circle.icon]]]]]
     [:div.ui.bottom.attached.grid.segment
      {:class (if (full-size?)
                "four column"
                "three column")
       :style {:padding "0px"}}
      (doall
       (->>
        criteria
        (map
         (fn [[cid criterion]]
           ^{:key {:article-label cid}}
           [with-tooltip
            [:div.ui.column
             {:data-content (:question criterion)
              :data-position "top left"
              :style {:background-color
                      (if (= (:name criterion) "overall include")
                        "rgba(200,200,200,1)"
                        nil)
                      :padding "0px"}}
             [:div.ui.middle.aligned.grid
              {:style {:margin "0px"}}
              [:div.ui.row
               {:style {:padding-bottom "0px"
                        :text-align "center"}}
               [:span
                {:style {:width "100%"}}
                (str (:short-label criterion) "?")]]
              [:div.ui.row
               {:style {:padding-top "8px"
                        :padding-bottom "18px"}}
               [:div
                {:style {:margin-left "auto"
                         :margin-right "auto"}}
                [three-state-selection
                 (fn [new-value]
                   (swap! state assoc-in
                          (concat labels-path [cid])
                          new-value)
                   (ajax/send-labels
                    article-id
                    (d/active-label-values article-id labels-path)))
                 (get label-values cid)]]]]]]))))]]))

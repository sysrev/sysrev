(ns sysrev-web.ui.article
  (:require
   [clojure.core.reducers :refer [fold]]
   [clojure.string :refer [capitalize]]
   [sysrev-web.base :refer [state]]
   [sysrev-web.state.data :refer [article-label-values user-label-values]]
   [sysrev-web.ui.components :refer
    [similarity-bar truncated-horizontal-list out-link label-value-tag]]
   [sysrev-web.util :refer [re-pos map-values]]))

(defn label-values-component [article-id & [user-id]]
  (fn [article-id & [user-id]]
    (let [cids (-> @state :data :criteria keys sort)
          values (if (nil? user-id)
                   (article-label-values article-id)
                   (user-label-values article-id user-id))]
      [:div.ui.header.content
       [:div.ui.horizontal.divided.list
        (doall
         (->>
          cids
          (map #(do [% (get values %)]))
          ;;(remove (fn [[k v]] (or (nil? v) (nil? (:answer v)))))
          (map
           (fn [[cid {answer :answer}]]
             (let [criteria-name
                   (get-in @state [:data :criteria cid :name])
                   answer-str (if (nil? answer)
                                "unknown"
                                (str answer))]
               ^{:key {:label-value (str cid "_" article-id)}}
               [:div.item {:style {:padding-left "0px"}}
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
      (let [score (:score article)
            similarity (- 1.0 (Math/abs score))]
        [:div.ui.segments
         [:div.ui.top.attached.segment
          [similarity-bar similarity]]
         [:div.ui.attached.segment
          [:h3.header
           [:a.ui.link {:href (str "/article/" article-id)}
            (:primary_title article)]
           (when-let [journal-name (:secondary_title article)]
             (str  " - " journal-name))]
          (when-not (empty? (:authors article))
            [:p (truncated-horizontal-list 5 (:authors article))])]
         [:div.ui.bottom.attached.segment
          (when (and show-labels
                     ((comp not empty?)
                      (user-label-values article-id user-id)))
            [label-values-component article-id user-id])]]))))

(defn article-info-component
  "Shows an article with a representation of its match quality and how it
  has been manually classified.
  `article-id` is required to specify the article.
  `show-labels` is a boolean (default false) specifying whether to display
  user values for labels on the article.
  `user-id` is optional, if specified then only input from that user will
  be included."
  [article-id & [show-labels user-id]]
  (fn [article-id & [show-labels user-id]]
    (when-let [article (get-in @state [:data :articles article-id])]
      (let [score (:score article)
            similarity (- 1.0 (Math/abs score))
            labels (and show-labels
                        (if user-id
                          (user-label-values article-id user-id)
                          (article-label-values article-id)))
            have-labels? (if labels true false)]
        [:div
         [:h3.ui.top.attached.header.segment
          "Article info"]
         (when-not (nil? score)
           [:div.ui.attached.segment
            [similarity-bar similarity]])
         [:div.ui
          {:class (if have-labels?
                    "attached segment"
                    "bottom attached segment")}
          [:div.content
           [:h3.header (:primary_title article)]
           (when-not (empty? (:secondary_title article))
             [:h3.header {:style {:margin-top "0px"}}
              (:secondary_title article)])
           (when-not (empty? (:authors article))
             [:p (truncated-horizontal-list 5 (:authors article))])
           [abstract (:abstract article)]
           [:div.content.ui.list
            (->> article :urls
                 (map-indexed
                  (fn [idx url]
                    ^{:key {:article-url {:aid article-id
                                          :url-idx idx}}}
                    [out-link url]))
                 doall)]]]
         (when have-labels?
           [:div.ui.bottom.attached.segment
            [:div.content
             [label-values-component article-id user-id]]])]))))

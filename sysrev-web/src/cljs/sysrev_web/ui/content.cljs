(ns sysrev-web.ui.content
  (:require [sysrev-web.base :refer [map-values re-pos]]
            [clojure.string :refer [capitalize]]
            [clojure.core.reducers :refer [fold]]))


; React's "dangerouslySetInnerHTML" capability, as suggested here:
; https://github.com/reagent-project/reagent/issues/14
; Our text data uses <sup> frequently, so this is mainly for that.
(defn dangerous
  ([comp content]
   (dangerous comp nil content))
  ([comp props content]
   [comp (assoc props :dangerouslySetInnerHTML {:__html content})]))

;; First pass over text, just breaks apart into groups of "Groupname: grouptext"
(defn- sections' [text]
  (let [group-header #"([A-Z][ A-Za-z]+):"
        groups (re-pos group-header text)
        ;; remove the colon:
        headers (map-values groups #(. % (slice 0 -1)) (sorted-map))
        windows (partition 2 1 headers)
        start-stop-names (map (fn [[[start name] [end _]]] [(+ (count name) start 1) end name]) windows)
        secs (->> start-stop-names
                  (map (fn [[start stop secname]] [secname (.. text (slice start stop) (trim))])))

        start-index (-> groups first first)]
    (cond
      (= start-index 0) (into [""] secs)
      (nil? start-index) [text]
      :else (into [(. text (slice 0 start-index))] secs))))

(defn sections
  "Take a blob of text, and look for sections such as \"Background: Text..\" Split this text into such sections.
  Return in the form: [preamble [secname sectext] [secname sectext] ...]. If no sections are found, all the text will
  end up in the preamble."
  [text]
  (let [secs (sections' text)]
    (into [(first secs)]
          (->> (vec (rest secs))
               (fold
                 (fn [acc [secname sec]]
                   (let [[lastsecname lastsec] (last acc)]
                     (cond
                       (nil? secname) (vec acc)
                       :else
                       (cond (nil? lastsecname) [[secname sec]]
                             ; Here, require the previous section ended with a period, to start a new section.
                             ; Split was correct. continue.
                             (= (last lastsec) ".") (conj (vec acc) [secname sec])
                             ; Require the last section had text. Otherwise merge section titles.
                             (empty? lastsec) (conj (vec (butlast acc)) [(str lastsecname " " secname) sec])
                             ; Split was incorrect, undo split.
                             :else (conj (vec (butlast acc)) [lastsecname (str lastsec ": " sec)]))))))))))


(defn abstract [text]
  (let [secs (sections text)]
    [:div
     [dangerous :p (first secs)]
     (->> (rest secs)
          (map-indexed
            (fn [idx [name text]]
              ^{:key idx}
              [:p [:strong (capitalize name)] ": " (dangerous :span text)])))]))

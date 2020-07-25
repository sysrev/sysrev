(ns sysrev.shared.keywords
  (:require [clojure.string :as str]
            [sysrev.util :refer [re-pos map-values]]))

;; First pass over text, just breaks apart into groups of "Groupname: grouptext"
(defn- sections' [text]
  (let [group-header #"(^|\s)([A-Za-z][A-Za-z /]+):"
        groups (re-pos group-header text)
        ;; remove the colon:
        headers (map-values #(subs % 0 (- (count %) 1)) (sorted-map) groups)
        windows (partition 2 1 headers)
        start-stop-names (map (fn [[[start name] [end _]]]
                                [(+ (count name) start 1) end name])
                              windows)
        secs (->> start-stop-names
                  (map (fn [[start stop secname]]
                         [secname (-> text (subs start stop) (str/trim))])))
        start-index (-> groups first first)]
    (cond
      (= start-index 0) (into [""] secs)
      (nil? start-index) [text]
      :else (into [(subs text 0 start-index)] secs))))

(defn sections
  "Take a blob of text, and look for sections such as \"Background: Text..\"
  Split this text into such sections.
  Return in the form: [preamble [secname sectext] [secname sectext] ...].
  If no sections are found, all the text will end up in the preamble."
  [text]
  (let [secs (sections' text)]
    (vec
     (concat
      [(first secs)]
      (->> (vec (rest secs))
           (reduce
            (fn [acc [secname sec]]
              (cond
                (nil? secname) (vec acc)
                (empty? acc) [[secname sec]]
                :else
                (let [[lastsecname lastsec] (last acc)]
                  (cond
                    (nil? lastsecname) [[secname sec]]
                    ;; Here, require the previous section ended with a period,
                    ;; to start a new section.
                    ;; Split was correct. continue.
                    (= (str (last lastsec)) ".")
                    (conj (vec acc) [secname sec])
                    ;; Require the last section had text.
                    ;; Otherwise merge section titles.
                    (empty? lastsec)
                    (conj (vec (butlast acc))
                          [(str lastsecname " " secname) sec])
                    ;; Split was incorrect, undo split.
                    :else (conj (vec (butlast acc))
                                [lastsecname (str lastsec ": " sec)])))))
            []))))))

(defn sections-to-text
  "Take a result from `sections` function and return something close to the
  original text."
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
          ["(" ")" "[" "]" "." "," "!" ";" ":" "*"]))

(defn canonical-keyword
  "Convert a token to standard format for comparison with keywords."
  [s]
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
  (letfn [(combine-toks [toks]
            (->> toks (map :raw) (str/join " ")))]
    (if (empty? rest-toks)
      (if (empty? pending)
        result
        (concat result [{:keyword-id nil
                         :text (combine-toks pending)}]))
      (let [kw-match (match-toks-keyword kmatch-vals rest-toks)]
        (if (nil? kw-match)
          (split-abstract-elts kmatch-vals
                               result
                               (conj pending (first rest-toks))
                               (rest rest-toks))
          (split-abstract-elts kmatch-vals
                               (concat result
                                       [{:keyword-id nil
                                         :text (combine-toks pending)}]
                                       [{:keyword-id (:keyword-id kw-match)
                                         :text (combine-toks (:keyword-toks kw-match))}])
                               []
                               (:rest-toks kw-match)))))))

(defn tokenize-article-text [text]
  (->> (str/split text #" ")
       (mapv (fn [s]
               {:raw s
                :canon (canonical-keyword s)}))))

;(defn process-keywords
;  "Processes text to find instances of keywords, and returns a map which can
;   be quickly converted to a Reagent element.
;   `keywords` is the value from (d/project-keywords) in client."
;  [text keywords]
;  (println (str "processing \n\ttext: " text "\n\tkeywords: " keywords))
;  (let [kmatch-vals (->> (vals keywords)
;                         (map #(select-keys % [:keyword-id :toks]))
;                         (map #(assoc % :toks (map canonical-keyword (:toks %))))
;                         (map #(assoc % :n-toks (count (:toks %))))
;                         (sort-by :n-toks >))
;        toks (tokenize-article-text text)]
;    (split-abstract-elts kmatch-vals [] [] toks)))


(defn process-keywords
  "Processes text to find instances of keywords, and returns a map which can
   be quickly converted to a Reagent element.
   `keywords` is the value from (d/project-keywords) in client."
  [text keywords]
    [{:keyword-id nil, :text text}])

(defn format-abstract
  "Splits abstract text into sections and processes each section for keywords."
  [text keywords]
  (let [unformatted? (nil? (str/index-of text "\n"))
        secs (and unformatted? (sections text))
        secs-text (and secs (sections-to-text secs))
        secs (if (and unformatted?
                      (>= (count secs-text) (* (count text) 0.9)))
               secs
               (str/split text #"\n"))]
    (->> secs
         (mapv
          (fn [sec]
            (let [sec-name (if (string? sec) nil (first sec))
                  sec-text (if (string? sec) sec (second sec))]
              {:name sec-name
               :content (process-keywords sec-text keywords)}))))))

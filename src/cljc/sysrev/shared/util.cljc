(ns sysrev.shared.util
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen])
  #?(:clj (:import java.util.UUID)))

(defn parse-integer
  "Reads a number from a string. Returns nil if not a number."
  [s]
  (if (integer? s) s
      (when (and (string? s) (re-find #"^ *\d+ *$" s))
        #?(:clj
           (try
             (Integer/parseInt s)
             (catch Throwable e
               (try
                 (read-string s)
                 (catch Throwable e2
                   nil))))

           :cljs
           (let [val (js/parseInt s)]
             (when (and (integer? val) (not= val ##NaN) (not (js/isNaN val)))
               val))))))

(defn parse-number
  "Reads a number from a string. Returns nil if not a number."
  [s]
  (if (number? s) s
      (if-let [int-val (parse-integer s)]
        int-val
        (when (and (string? s) (re-find #"^ *-?\d+\.?\d* *$" s))
          #?(:clj
             (read-string s)

             :cljs
             (let [val (js/parseFloat s)]
               (when (and (number? val) (not= val ##NaN) (not (js/isNaN val)))
                 val)))))))

#?(:clj
   ;; http://stackoverflow.com/questions/3262195/compact-clojure-code-for-regular-expression-matches-and-their-position-in-string
   (defn re-pos [re s]
     (loop [m (re-matcher re s)
            res (sorted-map)]
       (if (.find m)
         (recur m (assoc res (.start m) (.group m)))
         res)))
   :cljs
   ;; Slavish copy from stack overflow, get the position of occurence of regex, and the match.
   ;; Modified though to use a sorted map so we can have the result sorted by index.
   ;; https://stackoverflow.com/questions/18735665/how-can-i-get-the-positions-of-regex-matches-in-clojurescript
   (defn re-pos [re s]
     (let [re (js/RegExp. (.-source re) "g")]
       (loop [res (sorted-map)]
         (if-let [m (.exec re s)]
           (recur (assoc res (.-index m) (first m)))
           res)))))

(defn in?
  "Tests if `coll` contains an element equal to `x`.
  With one argument `coll`, returns the function #(in? coll %)."
  ([coll x] (some #(= x %) coll))
  ([coll] #(in? coll %)))

(defn map-values
  "Map a function over the values of a collection of pairs (vector of vectors,
  hash-map, etc.) Optionally accept a result collection to put values into."
  ([f rescoll m]
   (into rescoll (->> m (map (fn [[k v]] [k (f v)])))))
  ([f m]
   (map-values f {} m)))

(defn check
  "Returns val after running an assertion on `(f val)`.
  If `f` is not specified, checks that `(not (nil? val))`."
  [val & [f]]
  (let [f (or f (comp not nil?))]
    (assert (f val))
    val))

(defn conform-map [spec x]
  (and (s/valid? spec x)
       (->> (s/conform spec x)
            (apply hash-map))))

(s/def ::uuid uuid?)

(s/def ::uuid-or-str (s/or :uuid ::uuid :str string?))

(defn to-uuid [uuid-or-str]
  (let [in (conform-map ::uuid-or-str uuid-or-str)]
    (cond
      (contains? in :uuid) (:uuid in)
      (contains? in :str)
      #?(:clj (UUID/fromString (:str in))
         :cljs (uuid (:str in)))
      :else nil)))

(defn num-to-english [n]
  (get ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight"
        "nine" "ten" "eleven" "twelve" "thirteen" "fourteen" "fifteen"
        "sixteen"]
       n))

(defn short-uuid [uuid]
  (last (str/split (str uuid) #"\-")))

(defn integer-project-id? [url-id]
  (if (re-matches #"^[0-9]+$" url-id)
    true false))

(defn parse-html-str
  "Convert \"&lt;\" and \"&gt;\" to \"<\" and \">\"."
  [s]
  (-> s
      (str/replace #"\&lt;" "<")
      (str/replace #"\&gt;" ">")))

(defn pluralize
  "Add an 's' to end of string depending on count."
  [item-count string]
  (when string
    (cond-> string (not= item-count 1) (str "s"))))

(defn string-ellipsis
  "Shorten s using ellipsis in the middle when length is >= max-length."
  [s max-length & [ellipsis]]
  (let [ellipsis (or ellipsis "[...]")]
    (if (< (count s) max-length)
      s
      (str (subs s 0 (quot max-length 2))
           " " ellipsis " "
           (subs s (- (count s) (quot max-length 2)))))))

(defn ensure-prefix
  "Adds prefix at front of string s if not already present."
  [s prefix]
  (if (str/starts-with? s prefix)
    s
    (str prefix s)))

(defn ensure-suffix
  "Adds suffix at end of string s if not already present."
  [s suffix]
  (if (str/ends-with? s suffix)
    s
    (str s suffix)))

(defn random-id
  "Generate a random string id from uppercase/lowercase letters"
  ([len]
   (let [length (or len 6)
         char-gen (gen/fmap char (gen/one-of [(gen/choose 65 90)
                                              (gen/choose 97 122)]))]
     (apply str (gen/sample char-gen length))))
  ([] (random-id 6)))

(s/def ::class-condition (s/and vector? #(-> % count (mod 2) (= 0))))
(s/def ::class-form (s/or :null nil? :string string? :condition ::class-condition))

(defn css
  "Combines class forms into a space-separated CSS classes string.
  Each value should be of type string, vector, or nil. Vector forms
  are handled as if passing the values as arguments to cond; the
  values form pairs of (condition string), and the string from the
  first matching condition will be used. If no condition matches then
  no value will be included."
  [& class-forms]
  (->> class-forms
       (map (fn [x]
              (if (vector? x)
                (->> (partition 2 x) ;; group elements into (condition value) pairs
                     (filter first)  ;; filter by condition
                     (map second)    ;; extract class string value
                     first           ;; use first matching value (if any)
                     )
                x)))
       (remove #(contains? #{nil ""} %))
       (str/join " ")))
;;;
(s/fdef css
  :args (s/cat :class-forms (s/* ::class-form))
  :ret string?)

(defn keyword-argseq
  "Converts a keyword map to a flat sequence as used in syntax for
  function calls."
  [keyword-argmap]
  (->> keyword-argmap vec (apply concat)))

(defn apply-keyargs
  "Similar to apply but convenient for [... & {:keys ...}]
  functions. The last element of args must be a map of keyword args,
  which will be combined with the other args into a flat sequence
  before passing to apply."
  [f & args]
  (let [keyargs (last args)
        mainargs (butlast args)]
    (assert (and (map? keyargs)
                 (every? keyword? (keys keyargs)))
            (str "args has invalid last value: " (pr-str keyargs)))
    (apply f (concat mainargs (keyword-argseq keyargs)))))

(defn space-join
  "Joins a collection of strings after removing any empty values."
  [coll & {:keys [separator] :or {separator " "}}]
  (->> coll (map str) (remove empty?) (str/join separator)))

(defn wrap-parens
  "Wraps a string in directional paren characters."
  [s & {:keys [parens] :or {parens "()"}}]
  (when s (str (subs parens 0 1) s (subs parens 1 2))))

(defn ensure-value
  "Returns value if (test value) evaluates as logical true, otherwise
  returns nil. With one argument, returns a function using test that
  can be applied to a value."
  ([test] #(ensure-value test %))
  ([test value] (when (test value) value)))

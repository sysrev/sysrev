(ns sysrev.shared.util
  (:require [clojure.spec.alpha :as s]
            #?(:clj [orchestra.core :refer [defn-spec]]
               :cljs [orchestra.core :refer-macros [defn-spec]])
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [cognitect.transit :as transit])
  #?(:clj (:import java.util.UUID
                   (java.io ByteArrayOutputStream ByteArrayInputStream))))

(defn ensure-pred
  "Returns `value` if (`pred` `value`) returns logical true, otherwise
  returns nil. With one argument, returns a function using `pred` that
  can be applied to a value."
  ([pred] #(ensure-pred pred %))
  ([pred value] (when (pred value) value)))

(defn assert-pred
  "Returns `value` if (`pred` `value`) returns logical true, otherwise
  throws an assert exception. An assert message may optionally be
  provided by passing `pred` as a map with keys `:pred` and
  `:message`. With one argument, returns a function using `pred` that
  can be applied to a value."
  ([pred] #(assert-pred pred %))
  ([pred value]
   (let [{:keys [pred message]} (if (map? pred) pred {:pred pred})]
     (if message
       (assert (pred value) message)
       (assert (pred value)))
     value)))

(defn parse-integer
  "Reads a number from a string. Returns nil if not a number."
  [s]
  (if (integer? s) s
      (when (and (string? s) (re-find #"^ *\d+ *$" s))
        #?(:clj
           (try (Integer/parseInt s)
                (catch Throwable e
                  (try (->> (read-string s) (ensure-pred integer?))
                       (catch Throwable e2 nil))))
           :cljs
           (->> (js/parseInt s)
                (ensure-pred #(and (integer? %) (not= % ##NaN) (not (js/isNaN %)))))))))

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
  "Tests if `coll` contains an element equal to `x`. With one argument `coll`,
  returns the function #(in? coll %). Delegates to `contains?` for
  efficiency if `coll` is a set."
  ([coll x] (if (set? coll)
              (contains? coll x)
              (some #(= x %) coll)))
  ([coll] #(in? coll %)))

(defn map-values
  "Map a function over the values of a collection of pairs (vector of vectors,
  hash-map, etc.) Optionally accept a result collection to put values into."
  ([f rescoll m]
   (into rescoll (->> m (map (fn [[k v]] [k (f v)])))))
  ([f m]
   (map-values f {} m)))

(defn check
  "Returns val after running an assertion on (f val).
  If f is not specified, checks that (not (nil? val))."
  [val & [f]]
  (assert ((or f (comp not nil?)) val))
  val)

(defn conform-map [spec x]
  (and (s/valid? spec x)
       (apply hash-map (s/conform spec x))))

(s/def ::uuid uuid?)

(s/def ::uuid-or-str (s/or :uuid ::uuid :str string?))

(defn to-uuid [uuid-or-str]
  (let [in (conform-map ::uuid-or-str uuid-or-str)]
    (cond (contains? in :uuid)  (:uuid in)
          (contains? in :str)   #?(:clj (UUID/fromString (:str in))
                                   :cljs (uuid (:str in))))))

(defn num-to-english [n]
  (-> ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"
       "eleven" "twelve" "thirteen" "fourteen" "fifteen" "sixteen"]
      (get n)))

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
  (when string (cond-> string (not= item-count 1) (str "s"))))

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

(defn-spec css string?
  "Combines class forms into a space-separated CSS classes string.
  Each value should be of type string, vector, or nil. Vector forms
  are handled as if passing the values as arguments to cond; the
  values form pairs of (condition string), and the string from the
  first matching condition will be used. If no condition matches then
  no value will be included."
  [& class-forms (s/* ::class-form)]
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
    (when keyargs
      (assert (and (map? keyargs)
                   (every? keyword? (keys keyargs)))
              (str "args has invalid last value: " (pr-str keyargs))))
    (apply f (concat mainargs (keyword-argseq keyargs)))))

(defn space-join
  "Joins a collection of strings after removing any empty values."
  [coll & {:keys [separator] :or {separator " "}}]
  (->> coll (map str) (remove empty?) (str/join separator)))

(defn wrap-parens
  "Wraps a string in directional paren characters."
  [s & {:keys [parens] :or {parens "()"}}]
  (when s (str (subs parens 0 1) s (subs parens 1 2))))

(defn filter-keys
  "Returns a map of the entries in m for which (pred key) returns
  logical true."
  [pred m]
  (->> (seq m)
       (filter (fn [[k v]] (pred k)))
       (apply concat)
       (apply hash-map)))

(defn filter-values
  "Returns a map of the entries in m for which (pred value) returns
  logical true."
  [pred m]
  (->> (seq m)
       (filter (fn [[k v]] (pred v)))
       (apply concat)
       (apply hash-map)))

(defn write-transit-str [x]
  #?(:clj  (with-open [os (ByteArrayOutputStream.)]
             (let [w (transit/writer os :json)]
               (transit/write w x)
               (.toString os)))
     :cljs (-> (transit/writer :json)
               (transit/write x))))

(defn read-transit-str [s]
  #?(:clj  (-> (ByteArrayInputStream. (.getBytes s "UTF-8"))
               (transit/reader :json)
               (transit/read))
     :cljs (-> (transit/reader :json)
               (transit/read s))))

;; Slightly modified from clojure.core/group-by
(defn index-by
  "Variant of clojure.core/group-by for unique key values.

  Returns a map of the elements of coll keyed by the result of keyfn
  on each element. The elements of coll must all have a unique value
  for keyfn. The value at each key will be the single corresponding
  element."
  [keyfn coll]
  (persistent!
   (reduce
    (fn [ret x]
      (let [k (keyfn x)]
        (assert (= ::not-found (get ret k ::not-found))
                (str "index-by: duplicate key value (" k ")"))
        (assoc! ret k x)))
    (transient {}) coll)))

(defn or-default
  "If `value` is nil, returns `default`; otherwise returns `value`."
  [default value]
  (if (nil? value) default value))

(defmacro req-un [& keys]
  `(s/keys :req-un ~(into [] keys)))

(defmacro opt-keys [& keys]
  `(s/? (s/cat :keys (s/keys* :opt-un ~(into [] keys)))))

;;;
;;; Not used, keeping in case needed later
;;;
#_
(defn ^:unused integerify-map-keys
  "Maps parsed from JSON with integer keys will have the integers changed
  to keywords. This converts any integer keywords back to integers, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-int (and (keyword? k)
                                  (re-matches #"^\d+$" (name k))
                                  (sutil/parse-number (name k)))
                       k-new (if (integer? k-int) k-int k)
                       v-new (if (map? v) ; convert sub-maps recursively
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))
#_
(defn ^:unused uuidify-map-keys
  "Maps parsed from JSON with UUID keys will have the UUID values changed
  to keywords. This converts any UUID keywords back to UUID values, operating
  recursively through nested maps."
  [m]
  (if-not (map? m)
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-uuid (and (keyword? k)
                                   (->> (name k)
                                        (re-matches
                                         #"^[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+$"))
                                   (to-uuid (name k)))
                       k-new (if (uuid? k-uuid) k-uuid k)
                       v-new (if (map? v) ; convert sub-maps recursively
                               (uuidify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

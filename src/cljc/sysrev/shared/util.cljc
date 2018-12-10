(ns sysrev.shared.util
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
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
             (when (and (integer? val) (not= val ##NaN))
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
               (when (and (number? val) (not= val ##NaN))
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

(s/def ::uuid-or-str (s/or :uuid ::uuid
                           :str string?))

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


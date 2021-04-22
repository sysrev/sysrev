(ns sysrev.util
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test.check.generators :as gen]
            [cognitect.transit :as transit]
            #?@(:clj  [[orchestra.core :refer [defn-spec]]
                       [clj-time.core :as t]
                       [clj-time.coerce :as tc]
                       [clj-time.format :as tf]]
                :cljs [[orchestra.core :refer-macros [defn-spec]]
                       [cljs-time.core :as t]
                       [cljs-time.coerce :as tc]
                       [cljs-time.format :as tf]])
            #?@(:clj  [[clojure.main :refer [demunge]]
                       [clojure.data.json :as json]
                       [clojure.java.shell :refer [sh]]
                       [clojure.java.io :as io]
                       [clojure.pprint :as pp]
                       [clojure.tools.logging :as log]
                       [clojure.data.xml :as dxml]
                       [clojure.math.numeric-tower :as math]
                       [me.raynes.fs :as fs]
                       [crypto.random]
                       [venia.core :as venia]
                       [sysrev.config :refer [env]]
                       [sysrev.stacktrace :refer [print-cause-trace-custom]]]
                :cljs [["jquery" :as $]
                       ["moment" :as moment]
                       ["dropzone" :as Dropzone]
                       ["decamelize" :as decamelize]
                       [cljs-http.client :as http]
                       [goog.string :as gstr :refer [unescapeEntities]]
                       [goog.string.format]
                       [re-frame.core :refer [subscribe reg-event-db reg-fx]]
                       [sysrev.base :refer [active-route sysrev-hostname]]]))
  #?(:clj (:import [java.util UUID]
                   [java.util.zip GZIPInputStream]
                   [java.math BigInteger]
                   [java.io File ByteArrayOutputStream ByteArrayInputStream]
                   [org.joda.time DateTime]
                   [java.security MessageDigest])))

;;;
;;; CLJ+CLJS code
;;;

#?(:cljs (defn format
           "Wrapper to provide goog.string/format functionality from this namespace."
           [format-string & args]
           (apply gstr/format format-string args)))

(defn when-test
  "Returns `value` if (`pred` `value`) returns logical true, otherwise
  returns nil. With one argument, returns a function using `pred` that
  can be applied to a value."
  ([pred] #(when-test pred %))
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
                (catch Throwable _
                  (try (->> (read-string s) (when-test integer?))
                       (catch Throwable _ nil))))
           :cljs
           (->> (js/parseInt s)
                (when-test #(and (integer? %) (not= % ##NaN) (not (js/isNaN %)))))))))

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

(defn map-keys
  "Map a function over the keys of a collection of pairs (hash-map,
  vector of vectors, etc.) Optionally accepts a collection to put
  result into."
  ([f rescoll m]
   (into rescoll (->> m (map (fn [[k v]] [(f k) v])))))
  ([f m]
   (map-keys f {} m)))

(defn map-values
  "Map a function over the values of a collection of pairs (hash-map,
  vector of vectors, etc.) Optionally accepts a collection to put
  result into."
  ([f rescoll m]
   (into rescoll (->> m (map (fn [[k v]] [k (f v)])))))
  ([f m]
   (map-values f {} m)))

(defn map-kv
  "Map a function over the k-v pairs of a collection of pairs (hash-map,
  vector of vectors, etc.) f should take two arguments [key value] and
  return a two-element sequence of [key value]. Pairs for which f
  returns nil will be removed. Optionally accepts a collection to put
  result into."
  ([f rescoll m]
   (into rescoll (->> m (map (fn [[k v]] (f k v))) (remove nil?))))
  ([f m]
   (map-kv f {} m)))

(defn ^:unused check
  "Returns val after running an assertion on (f val).
  If f is not specified, checks that (not (nil? val))."
  [val & [f]]
  (assert ((or f (comp not nil?)) val))
  val)

(defn- uuid-str? [s]
  (boolean
   (and (string? s)
        (re-matches
         #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" s))))


(def email-regex
  #"[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")

(defn email? [s]
  (boolean
   (and (string? s)
        (re-matches email-regex s))))

(defn uuid-from-string [x]
  #?(:clj (UUID/fromString x)
     :cljs (uuid x)))

(defn to-uuid
  "Converts a value of types [uuid, string, keyword, symbol] to a uuid if possible,
  or returns nil if value cannot be converted to uuid."
  [x]
  (if (uuid? x) x
      (when (and (some #(% x) #{string? symbol? keyword?})
                 (uuid-str? (name x)))
        (uuid-from-string (name x)))))

(defn sanitize-uuids
  "Traverse `coll` and convert all uuid-like strings and keywords to uuid (java.util.UUID)."
  [coll]
  (walk/postwalk #(or (to-uuid %) %) coll))

(defn num-to-english [n]
  (-> ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"
       "eleven" "twelve" "thirteen" "fourteen" "fifteen" "sixteen"]
      (get n)))

(defn short-uuid [uuid]
  (-> (str uuid) (str/split #"\-") last))

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

(defn pluralized-count
  "Returns string of '`item-count` `string`(s)', pluralizing
  `string` based on `item-count`."
  [item-count string]
  (str item-count " " (pluralize item-count string)))

(defn ellipsis-middle
  "Shorten string `s` using `ellipsis` in the middle when >= `max-length`."
  [s max-length & [ellipsis]]
  (let [ellipsis (or ellipsis "[...]")]
    (if (< (count s) max-length)
      s
      (str (subs s 0 (quot max-length 2))
           " " ellipsis " "
           (subs s (- (count s) (quot max-length 2)))))))

(defn ellipsize
  "Shorten string `s` by ending with `ellipsis` when > `max-length`."
  [s max-length & [ellipsis]]
  (let [ellipsis (or ellipsis "...")]
    (cond-> s
      (> (count s) max-length)
      (-> (subs 0 (- max-length (count ellipsis)))
          (str ellipsis)))))

(defn ensure-prefix
  "Adds prefix at front of string s if not already present."
  [s prefix]
  (cond->> s
    (not (str/starts-with? s prefix))
    (str prefix)))

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
                (->> (partition 2 x) ; group elements into (condition value) pairs
                     (filter first)  ; filter by condition
                     (map second)    ; extract class string value
                     first         ; use first matching value (if any)
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
       (filter (fn [[k _v]] (pred k)))
       (apply concat)
       (apply hash-map)))

(defn filter-values
  "Returns a map of the entries in m for which (pred value) returns
  logical true."
  [pred m]
  (->> (seq m)
       (filter (fn [[_k v]] (pred v)))
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

#?(:clj  (defn write-json [x & [_pretty?]]
           (json/write-str x))
   :cljs (defn write-json [x & [pretty?]]
           (cond-> (clj->js x)
             pretty?        (js/JSON.stringify nil 2)
             (not pretty?)  (js/JSON.stringify))))

#?(:clj  (defn read-json
           "Parses a JSON string and on failure throws an exception reporting the
  invalid string input."
           [s & {:keys [keywords]
                 :or {keywords true}}]
           (try (if keywords
                  (json/read-str s :key-fn keyword)
                  (json/read-str s))
                (catch Throwable _
                  (throw (ex-info "Error parsing JSON string"
                                  {:string s})))))
   :cljs (defn read-json [s & {:keys [keywords]
                               :or {keywords true}}]
           (if keywords
             (js->clj (js/JSON.parse s) :keywordize-keys true)
             (js->clj (js/JSON.parse s)))))

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

(defn dissoc-in [m ks]
  (assert (sequential? ks) "dissoc-in: invalid ks")
  (if (= 1 (count ks))
    (dissoc m (last ks))
    (update-in m (butlast ks) #(dissoc % (last ks)))))

(defn or-default
  "If `value` is nil, returns `default`; otherwise returns `value`."
  [default value]
  (if (nil? value) default value))

(defmacro req-un [& keys]
  `(s/keys :req-un ~(into [] keys)))

(defmacro opt-keys [& keys]
  `(s/cat :keys (s/? (s/keys* :opt-un ~(into [] keys)))))

(defmacro assert-single [& syms]
  (let [show-syms (pr-str (seq syms))]
    `(let [count# (count (remove nil? [~@syms]))]
       (assert (> count# 0) (str "no value provided from " ~show-syms))
       (assert (< count# 2) (str "only one value allowed: " ~show-syms)))))

(defmacro assert-exclusive [& syms]
  (let [show-syms (pr-str (seq syms))]
    `(let [count# (count (remove nil? [~@syms]))]
       (assert (< count# 2) (str "multiple values not allowed: " ~show-syms)))))

(defmacro nilable-coll [pred & opts]
  `(s/nilable (s/coll-of ~pred ~@opts)))

(defmacro defspec-keys+partial
  "Defines spec from s/keys on `fields` using each of :req-un and :opt-un."
  [k-full k-partial fields]
  `(do (s/def ~k-full     (s/keys :req-un [~@fields]))
       (s/def ~k-partial  (s/keys :opt-un [~@fields]))
       [~k-full ~k-partial]))

(defmacro ignore-exceptions
  "Wraps `body` to silently handle all exceptions by returning nil."
  [& body]
  `(try ~@body (catch #?(:clj Throwable :cljs :default) _#
                 nil)))

(defn should-never-happen-exception []
  (ex-info "this should never happen" {:type :should-never-happen}))

(defn xml-find [roots path]
  (try (let [roots (if (map? roots) [roots] roots)]
         (if (empty? path)
           roots
           (xml-find (flatten
                      (map (fn [root]
                             (filter (fn [child]
                                       (= (:tag child) (first path)))
                                     (:content root)))
                           roots))
                     (rest path))))
       (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn xml-find-value [roots path]
  (-> (xml-find roots path) first :content first))

(defn xml-find-vector [roots path]
  (->> (xml-find roots path)
       (mapv #(-> % :content first))))

(defn today-string
  "Returns string of current date, by default in the form
  YYYYMMDD. Optional time-format value (default :basic-date) may be
  given as either a keyword (from clj-time.format/formatters) or a
  string (custom format via clj-time.format/formatter)."
  ([]
   (today-string :basic-date))
  ([time-format]
   (-> (cond (keyword? time-format) (tf/formatters time-format)
             (string? time-format)  (tf/formatter time-format)
             :else                  (tf/formatters :basic-date))
       (tf/unparse (t/now)))))

(defn parse-time-string
  "Returns DateTime (clj-time) object from :mysql format time
  string. Compatible with `write-time-string`."
  [s]
  (tf/parse (tf/formatter :mysql) s))

(defn now-ms
  "Returns current time in epoch milliseconds."
  []
  (tc/to-long (t/now)))

(defn time-from-epoch [epoch]
  (tc/from-long (* epoch 1000)))

;; from https://github.com/remvee/clj-base64/blob/master/src/remvee/base64.clj
(def base64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn bytes->base64
  "Encode sequence of bytes to a sequence of base64 encoded characters."
  [bytes]
  (letfn [(encode [bytes]
            (when (seq bytes)
              (let [t (->> bytes (take 3) (map #(bit-and (int %) 0xff)))
                    v (int (reduce (fn [a b] (+ (bit-shift-left (int a) 8) (int b))) t))
                    f #(nth base64-alphabet (bit-and (if (pos? %)
                                                       (bit-shift-right v %)
                                                       (bit-shift-left v (* % -1)))
                                                     0x3f))
                    r (condp = (count t)
                        1 (concat (map f [2 -4])    [\= \=])
                        2 (concat (map f [10 4 -2]) [\=])
                        3         (map f [18 12 6 0]))]
                (concat r (lazy-seq (encode (drop 3 bytes)))))))]
    (apply str (encode bytes))))

(defn url-join [& sections]
  (str/join "/" sections))

(defn email->name [s]
  (first (str/split s #"@")))

(defn ensure-vector [x]
  (cond (or (nil? x)
            (and (coll? x) (empty? x)))  nil
        (sequential? x)                  (vec x)
        :else                            [x]))

(defn sum [xs]
  (apply + xs))

;;;
;;; CLJ code
;;;

#?(:clj (defn parse-xml-str [s] (dxml/parse-str s)))

#?(:clj (defn all-project-ns []
          (->> (all-ns)
               (map ns-name)
               (map name)
               (filter #(re-find #"sysrev" %))
               (map symbol)
               (map find-ns))))

#?(:clj (defn ^:repl clear-project-symbols [syms]
          (let [syms (if (coll? syms) syms [syms])]
            (doseq [ns (all-project-ns)]
              (doseq [sym syms]
                (ns-unmap ns sym))))))

#?(:clj (defn ^:repl clear-project-aliases [alias]
          (doseq [ns (all-project-ns)]
            (ns-unalias ns alias))))

#?(:clj (defn ^:repl reload
          "Reload sysrev.user namespace to update referred symbols."
          []
          (require 'sysrev.user :reload)))

#?(:clj (defn ^:repl reload-all
          "Reload code for all project namespaces."
          []
          (let [ ;; Reload project namespaces
                failed (->> (all-project-ns)
                            (mapv #(try (require (ns-name %) :reload)
                                        nil
                                        (catch Throwable e
                                          (log/warn (ns-name %) "failed:" (.getMessage e))
                                          %)))
                            (remove nil?))]
            ;; Try again for any that failed
            ;; (may have depended on changes from another namespace)
            (doseq [ns failed]
              (require (ns-name ns) :reload))
            ;; Update sysrev.user symbols
            (reload))))

#?(:clj (defn crypto-rand []
          (let [size 4
                n-max (math/expt 256 size)
                n-rand (BigInteger. 1 (crypto.random/bytes size))]
            (double (/ n-rand n-max)))))

#?(:clj (defn crypto-rand-int [n]
          (let [size 4
                n-rand (BigInteger. 1 (crypto.random/bytes size))]
            (int (mod n-rand n)))))

;; see: https://groups.google.com/forum/#!topic/clojure/ORRhWgYd2Dk
;;      https://stackoverflow.com/questions/22116257/how-to-get-functions-name-as-string-in-clojure
#?(:clj (defmacro current-function-name
          "Returns a string, the name of the current Clojure function."
          []
          `(-> (Throwable.) .getStackTrace first .getClassName demunge)))

#?(:clj (defn to-clj-time
          "Converts various types to clj-time compatible DateTime. Integer
  values are treated as Unix epoch seconds. Tries to parse strings
  as :mysql format using `parse-time-string`."
          [t]
          (or (cond (= (type t) DateTime)             t
                    (= (type t) java.sql.Timestamp)   (tc/from-sql-time t)
                    (= (type t) java.sql.Date)        (tc/from-sql-date t)
                    (integer? t)                      (tc/from-epoch t)
                    (string? t)                       (parse-time-string t))
              (throw (ex-info "to-clj-time: unable to convert value" {:value t})))))

#?(:clj (defn write-time-string
          "Returns :mysql format time string from time value. Compatible with
  `parse-time-string`."
          [t & [formatter]]
          (tf/unparse (tf/formatters (or formatter :mysql))
                      (to-clj-time t))))

#?(:clj (defmacro with-print-time-elapsed
          "Runs `body` and logs elapsed time on completion."
          [name & body]
          `(let [start# (now-ms)]
             (try ~@body (finally (log/infof "[[ %s finished in %.2fs ]]"
                                             ~name (/ (- (now-ms) start#) 1000.0)))))))

;; see: https://stackoverflow.com/questions/10751638/clojure-rounding-to-decimal-places
#?(:clj (defn round
          "Round a double to the given precision (number of significant digits)"
          [precision d]
          (let [factor (Math/pow 10 precision)]
            (/ (Math/round (* d factor)) factor))))

#?(:clj (defn round-to
          "Round a double to the closest multiple of interval, then round to
  precision (number of significant digits).

  op controls which operation to use for the first round. Allowed
  values are [:round :floor :ceil]."
          [d interval precision & {:keys [op] :or {op :round}}]
          (let [x (/ d interval)]
            (->> interval
                 (* (case op
                      :round (Math/round x)
                      :floor (Math/floor x)
                      :ceil (Math/ceil x)))
                 (round precision)))))

;; see: https://gist.github.com/jizhang/4325757
#?(:clj (defn byte-array->md5-hash
          "Convert a byte-array into an md5 hash"
          [^"[B" bytes]
          (let [algorithm (MessageDigest/getInstance "MD5")
                raw (.digest algorithm bytes)]
            (format "%032x" (BigInteger. 1 raw)))))

#?(:clj (defn string->md5-hash
          "Convert a string into an md5 hash"
          [^String s]
          (byte-array->md5-hash (.getBytes s))))

#?(:clj (defn byte-array->sha-1-hash
          "Convert a byte-array into an md5 hash"
          [^"[B" bytes]
          (let [algorithm (MessageDigest/getInstance "SHA-1")
                raw (.digest algorithm bytes)]
            (format "%x" (BigInteger. 1 raw)))))

#?(:clj (defn file->byte-array
          "Convert a file into a byte-array"
          [^java.io.File file]
          (let [ary (byte-array (.length file))]
            (with-open [istream (java.io.FileInputStream. file)]
              (.read istream ary))
            ary)))

#?(:clj (defn file->sha-1-hash
          "Convert a file into a sha-1 hash"
          [^File file]
          (-> file
              file->byte-array
              byte-array->sha-1-hash)))

#?(:clj (defn slurp-bytes
          "Slurp the bytes from a slurpable thing.
  Taken from http://stackoverflow.com/a/26372677"
          [x]
          (with-open [out (ByteArrayOutputStream.)]
            (io/copy (io/input-stream x) out)
            (.toByteArray out))))

#?(:clj (defn wrap-retry
          [f & {:keys [fname max-retries retry-delay throttle-delay]
                :or {max-retries 3, retry-delay 1000, throttle-delay nil}}]
          (letfn [(result-fn [retry-count]
                    (when throttle-delay
                      (Thread/sleep throttle-delay))
                    (try
                      (f)
                      (catch Throwable e
                        (if (> (inc retry-count) max-retries)
                          (do (log/warn (format "wrap-retry [%s]: failed (%d retries)"
                                                (if fname (str fname) "unknown")
                                                max-retries))
                              (throw e))
                          (do (log/info (format "wrap-retry [%s]: retrying (%d / %d)"
                                                (if fname (str fname) "unknown")
                                                (inc retry-count)
                                                max-retries))
                              (Thread/sleep retry-delay)
                              (result-fn (inc retry-count)))))))]
            (result-fn 0))))

#?(:clj (defn shell
          "Runs a shell command, throwing exception on non-zero exit."
          [& args]
          (let [{:keys [exit] :as result}
                (apply sh args)]
            (if (zero? exit)
              result
              (throw (ex-info "shell command returned non-zero exit code"
                              {:type :shell
                               :command (vec args)
                               :result result}))))))

#?(:clj (defn create-tempfile [& {:keys [suffix]}]
          (let [file (File/createTempFile "sysrev-" suffix)]
            (.deleteOnExit file)
            file)))

#?(:clj (defn ^:repl rename-flyway-files
          "Handles renaming flyway sql files for formatting. Returns a sequence
  of shell commands that can be used to rename all of the
  files. directory should be path containing the sql
  files. primary-width and extra-width control zero-padding on index
  numbers in file names. mv argument sets shell command for mv
  (e.g. \"git mv\")."
          [directory & {:keys [primary-width extra-width mv]
                        :or {primary-width 4 extra-width 3 mv "mv"}}]
          (->> (fs/list-dir directory)
               (map #(str (fs/name %) (fs/extension %)))
               (map #(re-matches #"(V0\.)([0-9]+)(\.[0-9]+)*(__.*)" %))
               (map (fn [[original start n extra end]]
                      [(str start
                            (format (str "%0" primary-width "d") (parse-integer n))
                            (when extra
                              (->> (str/split extra #"\.")
                                   (map #(some->> (parse-integer %)
                                                  (format (str "%0" extra-width "d"))))
                                   (str/join ".")))
                            end)
                       original]))
               (filter (fn [[changed original]] (not= changed original)))
               sort
               (map (fn [[changed original]] (str/join " " [mv original changed]))))))

#?(:clj (defn ms-windows? []
          (-> (System/getProperty "os.name")
              (str/includes? "Windows"))))

#?(:clj (defn linux? []
          (some-> (System/getProperty "os.name")
                  (str/lower-case)
                  (str/includes? "linux"))))

#?(:clj (defn temp-dir []
          (str (or (:tmpdir env) (:java-io-tmpdir env))
               (if (ms-windows?) "\\" "/"))))

#?(:clj (defn tempfile-path [filename]
          (str (temp-dir) filename)))

#?(:clj (defmacro with-tempfile
          "Runs `body` with `file` bound to a new temporary file and ensures the
  file is deleted when this form completes."
          [[file & {:keys [suffix]}] & body]
          `(let [~file (create-tempfile :suffix ~suffix)]
             (try ~@body
                  (finally (io/delete-file ~file))))))

#?(:clj (defmacro with-gunzip-file
          "Decompresses gzipped file `input-file` and runs `body` with
  `tempfile` bound to the decompressed file, ensuring the temporary
  file is deleted when this form completes."
          [[tempfile input-file & {:keys [suffix]}] & body]
          `(with-open [gz-stream# (-> ~input-file io/file io/input-stream GZIPInputStream.)]
             (with-tempfile [~tempfile :suffix ~suffix]
               (io/copy gz-stream# ~tempfile)
               ~@body))))

#?(:clj (defn pp-str [x]
          (with-out-str (pp/pprint x))))

#?(:clj (defn random-uuid []
          (UUID/randomUUID)))

#?(:clj (defmacro log-exception [^Throwable e & {:keys [level] :or {level :error}}]
          `(let [e# ~e]
             (log/logf ~level "%s: %s\n%s"
                       (current-function-name) (.getMessage e#) (print-cause-trace-custom e#)))))

#?(:clj (defn gquery [query-form]
          (if (string? query-form)
            query-form ; return input value when already formatted as a query string
            (venia/graphql-query {:venia/queries query-form}))))

;;;
;;; CLJS code
;;;

#?(:cljs (defn scroll-top []
           (. js/window (scrollTo 0 0))
           nil))
#?(:cljs (reg-event-db :scroll-top #(do (scroll-top) (dissoc % :scroll-top))))
#?(:cljs (reg-fx :scroll-top (fn [_] (scroll-top))))

#?(:cljs (defn viewport-width []
           (. ($ js/window) (width))))

#?(:cljs (defn viewport-height []
           (. ($ js/window) (height))))

#?(:cljs (defn mobile? []
           (< (viewport-width) 768)))

#?(:cljs (defn full-size? []
           (>= (viewport-width) 992)))

#?(:cljs (defn desktop-size? []
           (>= (viewport-width) 1160)))

#?(:cljs (defn annotator-size? []
           (>= (viewport-width) 1100)))

#?(:cljs (def nbsp (unescapeEntities "&nbsp;")))

#?(:cljs (defn ^:unused number-to-word [n]
           (->> n (nth ["zero" "one" "two" "three" "four" "five" "six"
                        "seven" "eight" "nine" "ten" "eleven" "twelve"
                        "thirteen" "fourteen" "fifteen" "sixteen"]))))

#?(:cljs (defn url-domain
           "Gets the example.com part of a url"
           [url]
           (-> (some-> url http/parse-url :server-name (str/split #"\.")
                       (some->> (take-last 2) seq (str/join ".")))
               (or url))))

#?(:cljs (defn validate
           "Validate takes a map, and a map of validator/error message pairs, and
  returns a map of errors."
           [data validation]
           (->> validation
                (map (fn [[k [func message]]]
                       (when (not (func (k data))) [k message])))
                (into (hash-map)))))

#?(:cljs (defn time-elapsed-string [dt]
           (let [now (t/now)
                 intv (if (t/after? now dt)
                        (t/interval dt now)
                        (t/interval now now))
                 minutes (t/in-minutes intv)
                 hours (t/in-hours intv)
                 days (t/in-days intv)
                 weeks (t/in-weeks intv)
                 months (t/in-months intv)
                 years (t/in-years intv)
                 write (fn [n unit-name]
                         (format "%d %s ago" n (pluralize n unit-name)))]
             (cond (pos? years)   (write years "year")
                   (pos? months)  (write months "month")
                   (pos? weeks)   (write weeks "week")
                   (pos? days)    (write days "day")
                   (pos? hours)   (write hours "hour")
                   (pos? minutes) (write minutes "minute")
                   :else          "just now"))))

#?(:cljs (defn get-dom-elt [selector]
           (-> ($ selector) (.get 0))))

#?(:cljs (defn dom-elt-visible? [selector]
           (when-let [el (get-dom-elt selector)]
             (let [rect   (. el (getBoundingClientRect))
                   width  (or (-> js/window .-innerWidth)
                              (-> js/document .-documentElement .-clientWidth))
                   height (or (-> js/window .-innerHeight)
                              (-> js/document .-documentElement .-clientHeight))]
               (and (>= (.-top rect) 0)
                    (>= (.-left rect) 0)
                    (<= (.-bottom rect) height)
                    (<= (.-right rect) width))))))

#?(:cljs (defn scroll-to-dom-elt [selector]
           (some-> (get-dom-elt selector)
                   (.scrollIntoView true))))

#?(:cljs (defn ensure-dom-elt-visible
           "Scrolls to the position of DOM element if it is not currently visible."
           [selector]
           (when (false? (dom-elt-visible? selector))
             (scroll-to-dom-elt selector)
             (. js/window (scrollBy 0 -10)))))

#?(:cljs (defn ensure-dom-elt-visible-soon
           "Runs `ensure-dom-elt-visible` using js/setTimeout at multiple delay times."
           [selector]
           (doseq [ms [10 25 50 100]]
             (js/setTimeout #(ensure-dom-elt-visible selector) ms))))

#?(:cljs (defn continuous-update-until
           "Call f continuously every n milliseconds until pred is satisified. pred
  must be a fn. on-success (unless nil) will be called one time after
  pred is satisified."
           [f pred on-success n]
           (js/setTimeout #(if (pred)
                             (when on-success (on-success))
                             (do (f)
                                 (continuous-update-until f pred on-success n)))
                          n)))

#?(:cljs (defonce ^:private run-after-condition-state (atom #{})))

#?(:cljs (defn run-after-condition
           "Run function `on-ready` as soon as function `is-ready` returns
  logical true, re-checking every `interval` ms. `id` can be any value
  and should identify this call to `run-after-condition` in order to
  prevent multiple duplicate instances from running simultaneously.
  `is-abort` is optionally a function that will be checked along with
  `is-ready`, and if returns true this will be aborted without running
  `on-ready`."
           [id is-ready on-ready & {:keys [interval is-abort]
                                    :or {interval 15
                                         is-abort (constantly false)}}]
           ;; Check if a `run-after-condition` instance is already active
           ;; for this `id` value; if so, don't start a new one.
           (when-not (contains? @run-after-condition-state id)
             (swap! run-after-condition-state #(conj % id))
             (letfn [(run []
                       (cond (is-ready)
                             ;; Finished; remove `id` from list of active `run-after-condition`
                             ;; instances and run final `on-ready` function.
                             (do (swap! run-after-condition-state
                                        #(set (remove (partial = id) %)))
                                 (on-ready))
                             ;; If `is-abort` function was provided and returns true,
                             ;; abort this loop and remove `id` from list.
                             (and is-abort (is-abort))
                             (swap! run-after-condition-state
                                    #(set (remove (partial = id) %)))
                             ;; Condition not yet true; try again after `interval` ms.
                             :else (js/setTimeout run interval)))]
               ;; Begin loop
               (run)))))

#?(:cljs (defn event-input-value
           "Returns event.target.value from a DOM event."
           [event]
           (-> event .-target .-value)))

#?(:cljs (defn input-focused? []
           (when-let [el js/document.activeElement]
             (when (or (.is ($ el) "input")
                       (.is ($ el) "textarea"))
               el))))

;; https://stackoverflow.com/questions/3169786/clear-text-selection-with-javascript
#?(:cljs (defn clear-text-selection
           "Clears any user text selection in window."
           []
           ;; don't run if input element is focused, will interfere with focus/behavior
           (when-not (input-focused?)
             (cond (-> js/window .-getSelection)
                   (cond (-> js/window (.getSelection) .-empty) ;; Chrome
                         (-> js/window (.getSelection) (.empty))

                         (-> js/window (.getSelection) .-removeAllRanges) ;; Firefox
                         (-> js/window (.getSelection) (.removeAllRanges)))

                   (-> js/document .-selection) ;; IE?
                   (-> js/document .-selection (.empty))))))

#?(:cljs (defn clear-text-selection-soon
           "Runs clear-text-selection after short js/setTimeout delays."
           []
           (doseq [ms [5 25 50]]
             (js/setTimeout #(clear-text-selection) ms))))

#?(:cljs (defn wrap-prevent-default
           "Wraps an event handler function to prevent execution of a default
  event handler. Tested for on-submit event."
           [f]
           (when f
             (fn [event]
               (f event)
               (when (.-preventDefault event)
                 (.preventDefault event))
               #_ (set! (.-returnValue event) false)
               false))))

#?(:cljs (defn wrap-stop-propagation
           [f]
           (when f
             (fn [event]
               (when (.-stopPropagation event)
                 (.stopPropagation event))
               (f event)))))

#?(:cljs (defn wrap-user-event
           "Wraps an event handler for an event triggered by a user click.

  Should be used for all such events (e.g. onClick, onSubmit).

  Handles issue of unintentional text selection on touchscreen devices.

  {
    f :
      Base event handler function; `(fn [event] ...)`
      `wrap-user-event` will return nil when given nil value for `f`.
    timeout :
      Default false. When true, runs inner handler via `js/setTimeout`.
      This breaks (at least) ability to access `(.-target event)`.
    prevent-default :
      Adds wrap-prevent-default at outermost level of handler.
    stop-propagation :
      Adds `(.stopPropagation event)` before inner handler executes.
    clear-text-after :
      When true (default), runs `(clear-text-selection-soon)` after
      inner handler executes.
    clear-text-before :
      When true, runs `(clear-text-selection)` before inner handler executes.
      Defaults to true when `timeout` is set to false.
  }"
           [f & {:keys [timeout prevent-default stop-propagation
                        clear-text-after clear-text-before]
                 :or {timeout false
                      prevent-default false
                      stop-propagation false
                      clear-text-after true
                      clear-text-before nil}}]
           (when f
             (let [clear-text-before (if (boolean? clear-text-before)
                                       clear-text-before
                                       (not timeout))
                   wrap-handler (fn [event]
                                  (when clear-text-before (clear-text-selection))
                                  (let [result (f event)]
                                    (when clear-text-after (clear-text-selection-soon))
                                    result))]
               (cond-> (fn [event]
                         ;; Add short delay before processing event to allow touchscreen
                         ;; events to resolve.
                         (if timeout
                           (js/setTimeout #(wrap-handler event) 20)
                           (wrap-handler event))
                         true)
                 stop-propagation  (wrap-stop-propagation)
                 prevent-default   (wrap-prevent-default))))))

#?(:cljs (defn on-event-value
           "Convenience function for processing input values from events. Takes a
  function which receives event input value and performs some side
  effect; returns a DOM event handler function (for :on-change etc)."
           [handler]
           (wrap-prevent-default #(-> % event-input-value (handler)))))

#?(:cljs (defn no-submit
           "Returns on-submit handler to block default action on forms."
           []
           (wrap-prevent-default (fn [_] nil))))

;; https://www.kirupa.com/html5/get_element_position_using_javascript.htm
#?(:cljs (defn get-element-position [el]
           (letfn [(get-position [el x y]
                     (let [next-el ($ el :offsetParent)
                           [next-x next-y]
                           (if (= ($ el :tagName) "BODY")
                             (let [xscroll (if (number? ($ el :scrollLeft))
                                             ($ el :scrollLeft)
                                             (-> ($ js/document :documentElement)
                                                 ($ :scrollLeft)))
                                   yscroll (if (number? ($ el :scrollTop))
                                             ($ el :scrollTop)
                                             (-> ($ js/document :documentElement)
                                                 ($ :scrollTop)))]
                               [(+ x
                                   ($ el :offsetLeft)
                                   (- xscroll)
                                   ($ el :clientLeft))
                                (+ y
                                   ($ el :offsetTop)
                                   (- yscroll)
                                   ($ el :clientTop))])
                             [(+ x
                                 ($ el :offsetLeft)
                                 (- ($ el :scrollLeft))
                                 ($ el :clientLeft))
                              (+ y
                                 ($ el :offsetTop)
                                 (- ($ el :scrollTop))
                                 ($ el :clientTop))])]
                       (if (or (nil? next-el) (undefined? next-el))
                         {:top next-y :left next-x}
                         (get-position next-el next-x next-y))))]
             (when-not (or (nil? el) (undefined? el))
               (get-position el 0 0)))))

#?(:cljs (defn get-scroll-position []
           {:top  (or ($ js/window :pageYOffset)
                      (-> ($ js/document :documentElement) ($ :scrollTop)))
            :left (or ($ js/window :pageXOffset)
                      (-> ($ js/document :documentElement) ($ :scrollLeft)))}))

#?(:cljs (defn get-url-path []
           (str js/window.location.pathname
                js/window.location.search
                js/window.location.hash)))



#?(:cljs (defn parse-css-px [px-str]
           (parse-integer (second (re-matches #"(\d+)px" px-str)))))

#?(:cljs (defn update-sidebar-height []
           (when (pos? (.-length ($ ".column.panel-side-column")))
             (let [total-height (viewport-height)
                   header-height (or (some-> ($ "div.menu.site-menu") (.height)) 0)
                   footer-height (or (some-> ($ "div#footer") (.height)) 0)
                   body-font (or (some-> ($ "body") (.css "font-size") parse-css-px) 14)
                   max-height-px (- total-height
                                    (+ header-height footer-height
                                       ;; "Labels / Annotations" menu
                                       (* 4 body-font)
                                       ;; "Save / Skip" buttons
                                       (* 5 body-font)
                                       ;; Extra space
                                       (* 4 body-font)))
                   label-css ".panel-side-column .ui.segments.label-editor-view"
                   annotate-css ".ui.segments.annotation-menu.abstract"
                   label-font (or (some-> ($ label-css) (.css "font-size") parse-css-px)
                                  body-font)
                   annotate-font (or (some-> ($ annotate-css) (.css "font-size") parse-css-px)
                                     body-font)
                   label-height-em (/ (* 1.0 max-height-px) label-font)
                   annotate-height-em (/ (* 1.0 max-height-px) annotate-font)]
               (-> ($ label-css)    (.css "max-height" (str label-height-em "em")))
               (-> ($ annotate-css) (.css "max-height" (str annotate-height-em "em")))))))

#?(:cljs (defn unix-epoch->date-string [unix]
           (-> unix (moment/unix) (.format "YYYY-MM-DD HH:mm:ss"))))

#?(:cljs (defn condensed-number
           "Condense numbers over 1000 to be factors of k"
           [i]
           (if (> i 999)
             (-> (/ i 1000) (.toFixed 1) (str "K"))
             (str i))))

#?(:cljs (defn ui-theme-from-dom-css []
           (cond (pos? (.-length ($ "link[href='/css/style.dark.css']")))
                 "Dark"
                 (pos? (.-length ($ "link[href='/css/style.default.css']")))
                 "Default")))

#?(:cljs (defn log
           "Wrapper to run js/console.log using printf-style formatting."
           [format-string & args]
           (js/console.log (apply format format-string args)) nil))

#?(:cljs (defn log-err
           "Wrapper to run js/console.error using printf-style formatting."
           [format-string & args]
           (js/console.error (apply format format-string args)) nil))

#?(:cljs (defn log-warn
           "Wrapper to run js/console.warn using printf-style formatting."
           [format-string & args]
           (js/console.warn (apply format format-string args)) nil))

#?(:cljs (defn ^:export add-dropzone-file-blob [to-blob base64-image]
           (let [zone (Dropzone/Dropzone.forElement ".dropzone")
                 blob (to-blob base64-image "image / png")]
             (.addFile zone blob))))

#?(:cljs (defn clojurize-map
           "Ensures that (1) `val` is a Clojure value, then if `val` is a map
  (2) converts any keys from camel-case to dash-separated, and from
  string to keyword. Recurses into values which are themselves maps."
           [val]
           (let [val (js->clj val)]
             (->> (cond-> val
                    (map? val)
                    (->> (map-keys #(cond-> %
                                      (or (keyword? %) (string? %))
                                      (-> name (decamelize "-") keyword)))
                         (map-values #(if (map? %)
                                        (clojurize-map %)
                                        %))))))))
#?(:cljs (defn base64->uint8 [base64]
           (-> base64 js/atob (js/Uint8Array.from #(.charCodeAt % 0)))))

#?(:cljs (defn cents->dollars
           "Converts an integer value of cents to dollars"
           [cents]
           (str (-> cents (/ 100) (.toFixed 2)))))

#?(:cljs (defn round [x]
           (js/Math.round x)))

#?(:cljs (defn read-sub
           "Creates a variable-argument function that will dereference
  re-frame subscription `query-id` with the supplied arguments.

  If an optional `args` sequence is passed, applies the function
  to those arguments."
           ([query-id]       (fn [& args]
                               @(subscribe (vec (concat [query-id] args)))))
           ([query-id args]  (apply (read-sub query-id) args))))

#?(:cljs (defn get-url-params []
           (:query-params (http/parse-url @active-route))))

#?(:cljs (defn humanize-url
           "Returns human-formatted link text based on href value.
  Displays internal links as \"sysrev.com/...\""
           [href]
           (let [p (http/parse-url href)]
             (cond (empty? (:server-name p))
                   (str sysrev-hostname
                        (when-not (= href "/") href))

                   (in? #{:http :https} (:scheme p))
                   (str (:server-name p)
                        (some->> (:server-port p) (str ":"))
                        (if (str/ends-with? (:uri p) "/")
                          (apply str (butlast (:uri p)))
                          (:uri p))
                        (some->> (:query-string p) (str "?")))

                   :else href))))

(defn data-matches? [s1 s2]
  (and s1 s2 (str/includes? (str/lower-case s1) (str/lower-case s2))))

(defn data-filter [items fns text]
  (filter
    (fn [item]
      (some
        (fn [f]
          (let [v (f item)]
            (data-matches? (str v) (str text))))
        fns))
    items))

(defn should-robot-index? [uri]
  (boolean
    (or
      (= uri "/")
      (= uri "/pricing")
      (= uri "/terms-of-use")
      (re-matches #"/p/\d+$" uri)
      (re-matches #"/u/\d+/p/\d+$" uri))))

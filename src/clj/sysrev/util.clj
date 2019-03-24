(ns sysrev.util
  (:require [clojure.main :refer [demunge]]
            [clojure.tools.logging :as log]
            [clojure.xml]
            [crypto.random]
            [clojure.math.numeric-tower :as math]
            [cognitect.transit :as transit]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tformat]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [sysrev.shared.util :as shared])
  (:import (javax.xml.parsers SAXParser SAXParserFactory)
           java.util.UUID
           (java.io ByteArrayOutputStream)
           (java.io ByteArrayInputStream)
           java.security.MessageDigest
           java.math.BigInteger
           java.io.File))

(defn integerify-map-keys
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
                                  (shared/parse-number (name k)))
                       k-new (if (integer? k-int) k-int k)
                       ;; integerify sub-maps recursively
                       v-new (if (map? v)
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn uuidify-map-keys
  "Maps parsed from JSON with UUID keys will have the UUID values changed
  to keywords. This converts any UUID keywords back to UUID values, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-uuid
                       (and (keyword? k)
                            (re-matches
                             #"^[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+\-[\da-f]+$"
                             (name k))
                            (UUID/fromString (name k)))
                       k-new (if (= UUID (type k-uuid)) k-uuid k)
                       ;; uuidify sub-maps recursively
                       v-new (if (map? v)
                               (uuidify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn should-never-happen-exception []
  (ex-info "this should never happen" {:type :should-never-happen}))

(defn xml-find [roots path]
  (try
    (let [roots (if (map? roots) [roots] roots)]
      (if (empty? path)
        roots
        (xml-find
         (flatten
          (map (fn [root]
                 (filter (fn [child]
                           (= (:tag child) (first path)))
                         (:content root)))
               roots))
         (rest path))))
    (catch Throwable e
      nil)))

(defn xml-find-value [roots path]
  (-> (xml-find roots path) first :content first))

(defn xml-find-vector [roots path]
  (->> (xml-find roots path)
       (mapv #(-> % :content first))))

(defn parse-xml-str [s]
  (let [;; Create parser instance with DTD loading disabled.
        ;; Without this, parser may make HTTP requests to DTD locations
        ;; referenced in the XML string.
        startparse-no-dtd
        (fn [s ch]
          (let [^SAXParserFactory factory (SAXParserFactory/newInstance)]
            (.setFeature factory "http://apache.org/xml/features/nonvalidating/load-external-dtd" false)
            (let [^SAXParser parser (.newSAXParser factory)]
              (.parse parser s ch))))]
    (clojure.xml/parse
     (java.io.ByteArrayInputStream. (.getBytes s))
     startparse-no-dtd)))

(defn all-project-ns []
  (->> (all-ns)
       (map ns-name)
       (map name)
       (filter #(re-find #"sysrev" %))
       (map symbol)
       (map find-ns)))

(defn clear-project-symbols [syms]
  (let [syms (if (coll? syms) syms [syms])]
    (doseq [ns (all-project-ns)]
      (doseq [sym syms]
        (ns-unmap ns sym)))))

(defn clear-project-aliases [alias]
  (doseq [ns (all-project-ns)]
    (ns-unalias ns alias)))

(defn reload
  "Reload sysrev.user namespace to update referred symbols."
  []
  (require 'sysrev.user :reload))

(defn reload-all
  "Reload code for all project namespaces."
  []
  (let [ ;; Reload project namespaces
        failed (->> (all-project-ns)
                    (mapv #(try
                             (do (require (ns-name %) :reload) nil)
                             (catch Throwable e
                               (log/warn (ns-name %) "failed:" (.getMessage e))
                               %)))
                    (remove nil?))]
    ;; Try again for any that failed
    ;; (may have depended on changes from another namespace)
    (doseq [ns failed]
      (require (ns-name ns) :reload))
    ;; Update sysrev.user symbols
    (reload)))

(defn map-to-arglist
  "Converts a map to a vector of function keyword arguments."
  [m]
  (->> m (mapv identity) (apply concat) vec))

(defn crypto-rand []
  (let [size 4
        n-max (math/expt 256 size)
        n-rand (BigInteger. 1 (crypto.random/bytes size))]
    (double (/ n-rand n-max))))

(defn crypto-rand-int [n]
  (let [size 4
        n-rand (BigInteger. 1 (crypto.random/bytes size))]
    (int (mod n-rand n))))

(defn crypto-rand-nth [coll]
  (nth coll (crypto-rand-int (count coll))))

(defn write-transit-str [x]
  (with-open [os (ByteArrayOutputStream.)]
    (let [w (transit/writer os :json)]
      (transit/write w x)
      (.toString os))))

(defn read-transit-str [s]
 (-> (ByteArrayInputStream. (.getBytes s "UTF-8"))
     (transit/reader :json)
     (transit/read)))

;; see: https://groups.google.com/forum/#!topic/clojure/ORRhWgYd2Dk
;;      https://stackoverflow.com/questions/22116257/how-to-get-functions-name-as-string-in-clojure
(defmacro current-function-name
  "Returns a string, the name of the current Clojure function."
  []
  `(-> (Throwable.) .getStackTrace first .getClassName demunge))

(defn today-string
  "Returns string of current date, by default in the form
  YYYYMMDD. Optional time-format value (default :basic-date) may be
  given as either a keyword (from clj-time.format/formatters) or a
  string (custom format via clj-time.format/formatter)."
  ([]
   (today-string :basic-date))
  ([time-format]
   (-> (cond (keyword? time-format) (tformat/formatters time-format)
             (string? time-format)  (tformat/formatter time-format)
             :else                  (tformat/formatters :basic-date))
       (tformat/unparse (t/now)))))

(defn now-unix-seconds []
  (-> (t/now) (tc/to-long) (/ 1000) int))

;; see: https://stackoverflow.com/questions/10751638/clojure-rounding-to-decimal-places
(defn round
  "Round a double to the given precision (number of significant digits)"
  [precision d]
  (let [factor (Math/pow 10 precision)]
    (/ (Math/round (* d factor)) factor)))

(defn round-to
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
         (round precision))))

;; see: https://gist.github.com/jizhang/4325757
(defn byte-array->md5-hash
  "Convert a byte-array into an md5 hash"
  [^"[B" bytes]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm bytes)]
    (format "%032x" (BigInteger. 1 raw))))

(defn string->md5-hash
  "Convert a string into an md5 hash"
  [^String s]
  (byte-array->md5-hash (.getBytes s)))

(defn byte-array->sha-1-hash
  "Convert a byte-array into an md5 hash"
  [^"[B" bytes]
  (let [algorithm (MessageDigest/getInstance "SHA-1")
        raw (.digest algorithm bytes)]
    (format "%x" (BigInteger. 1 raw))))

(defn string->sha-1-hash
  "Convert a string into a sha-1 hash"
  [^String s]
  (byte-array->sha-1-hash (.getBytes s)))

(defn file->byte-array
  "Convert a file into a byte-array"
  [^java.io.File file]
  (let [ary (byte-array (.length file))]
    (with-open [istream (java.io.FileInputStream. file)]
      (.read istream ary))
    ary))

(defn file->sha-1-hash
  "Convert a file into a sha-1 hash"
  [^java.io.File file]
  (-> file
      file->byte-array
      byte-array->sha-1-hash))

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing.
  Taken from http://stackoverflow.com/a/26372677"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))

(defn wrap-retry
  [f & {:keys [fname max-retries retry-delay throttle-delay]
        :or {max-retries 10
             retry-delay 2000
             throttle-delay nil}}]
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
    (result-fn 0)))

(defn vector->hash-map
  "Convert a vector into a hash-map with keys that correspond to the val of kw in each element"
  [v kw]
  (->> v
       (map #(hash-map (kw %) %))
       (apply merge)))

(defn shell
  "Runs a shell command, throwing exception on non-zero exit."
  [& args]
  (let [{:keys [exit out err] :as result}
        (apply sh args)]
    (if (zero? exit)
      result
      (do (log/error (str (pr-str args) ":") "Got exit code" exit)
          (log/error (str "stdout\n" out))
          (log/error (str "stderr\n" err))
          (throw (Exception. (pr-str result)))))))

(defn create-tempfile [& {:keys [suffix]}]
  (let [file (File/createTempFile "sysrev-" suffix)]
    (.deleteOnExit file)
    file))

(defn ex-summary
  "Returns string showing type and message from exception."
  [ex]
  (str (type ex) " - " (.getMessage ex)))

;; from https://github.com/remvee/clj-base64/blob/master/src/remvee/base64.clj
(def base64-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
;;
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

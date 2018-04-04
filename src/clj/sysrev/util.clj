(ns sysrev.util
  (:require [clojure.main :refer [demunge]]
            [clojure.xml]
            [crypto.random]
            [clojure.math.numeric-tower :as math]
            [cognitect.transit :as transit]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tformat]
            [clojure.java.io :as io])
  (:import (javax.xml.parsers SAXParser SAXParserFactory)
           java.util.UUID
           (java.io ByteArrayOutputStream)
           (java.io ByteArrayInputStream)))

(defn parse-number
  "Reads a number from a string. Returns nil if not a number."
  [s]
  (when (and (string? s) (re-find #"^-?\d+\.?\d*$" s))
    (read-string s)))

(defn parse-integer
  "Reads a number from a string. Returns nil if not a number."
  [s]
  (when-let [n (parse-number s)]
    (when (integer? n)
      n)))

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
                                  (parse-number (name k)))
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

(defn reload []
  (require 'sysrev.user :reload))

(defn reload-all []
  (require 'sysrev.user :reload-all))

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

(defn today-string []
  (let [now (t/now)
        fmt (tformat/formatters :basic-date)]
    (tformat/unparse fmt now)))

;; see: https://stackoverflow.com/questions/10751638/clojure-rounding-to-decimal-places
(defn round
  "Round a double to the given precision (number of significant digits)"
  [precision d]
  (let [factor (Math/pow 10 precision)]
    (/ (Math/round (* d factor)) factor)))

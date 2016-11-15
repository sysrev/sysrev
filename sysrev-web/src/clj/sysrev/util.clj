(ns sysrev.util
  (:require [clojure.xml])
  (:import (javax.xml.parsers SAXParser SAXParserFactory)))

(defn map-values
  "Map a function over the values of a collection of pairs (vector of vectors,
  hash-map, etc.) Optionally accept a result collection to put values into."
  ([f rescoll m]
   (into rescoll (->> m (map (fn [[k v]] [k (f v)])))))
  ([f m]
   (map-values f {} m)))

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

(defn in?
  "Tests if `coll` contains an element equal to `x`.
  With one argument `coll`, returns the function #(in? coll %)."
  ([coll x] (some #(= x %) coll))
  ([coll] #(in? coll %)))

(defn integerify-map-keys
  "Maps parsed from JSON with integer keys will have the integers changed
  to keywords. This converts any integer keywords back to integers, operating
  recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-int (-> k name parse-number)
                       k-new (if (integer? k-int) k-int k)
                       ;; integerify sub-maps recursively
                       v-new (if (map? v)
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn should-never-happen-exception []
  (ex-info "this should never happen" {:type :should-never-happen}))

(defn xml-find [roots path]
  (let [roots (if (map? roots) [roots] roots)]
    (if (empty? path)
      roots
      (xml-find
       (flatten
        (mapv (fn [root]
                (filterv (fn [child]
                           (= (:tag child) (first path)))
                         (:content root)))
              roots))
       (rest path)))))

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
          (let [factory (SAXParserFactory/newInstance)]
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

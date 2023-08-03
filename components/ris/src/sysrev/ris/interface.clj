(ns sysrev.ris.interface
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [instaparse.core :as insta :refer [defparser]]
            [instaparse.transform :refer [transform]])
  (:import [java.io BufferedReader StringReader]))

;; Moved from https://github.com/insilica/datasource/blob/dev/src/clj/datasource/ris.clj

;;;
;; practical use of instaparse:
;; http://www.lexicallyscoped.com/2015/07/27/parse-edifact-w-clojure-instaparse.html
(declare ris-parser)
(defparser ris-parser
  (slurp (io/resource "sysrev/ris/bnf/ris.bnf")))

(declare ris-newline-parser)
(defparser ris-newline-parser
  (slurp (io/resource "sysrev/ris/bnf/ris_newline.bnf")))

(declare ris-refworks-parser)
(defparser ris-refworks-parser
  (slurp (io/resource "sysrev/ris/bnf/ris_refworks.bnf")))

(defn transform-line
  [parse-tree]
  (transform {:LINE (fn [tag val]
                      {(keyword (second tag))
                       (second val)})}
             parse-tree))

(defn merge-lines
  "Given a coll of tag maps, condense them to a single map"
  ([coll]
   (merge-lines coll {}))
  ([coll m]
   (if (seq coll)
     (let [[kw v] (first (first coll))
           current-val (kw m)
           new-map (assoc m kw
                          (if current-val
                            (-> [current-val]
                                (conj v) flatten ((partial into [])))
                            [v]))]
       (merge-lines (rest coll) new-map))
     ;; return the resulting m
     m)))

(defn parser-output->coll
  "Transform parser output to a coll of citation hash-maps of the form
    {:<TAG_1> [<VAL_1>, ... , <VAL_i>]
    ...
     :<TAG_N> [<VAL_1>, ..., <VAL_i]>}"
  [coll]
  (->> coll
       rest
       (map transform-line)
       (map rest)
       (map merge-lines)
       (filter seq)))

(defn remove-boms
  "Remove Unicode Byte Order Marks"
  [^String s]
  (-> s
      (str/replace "\uFEFF" "") ;; UTF-8 BOM
      (str/replace "\uFFFE" "") ;; UTF-32 Little Endian BOM
      (str/replace "\u0000FEFF" ""))) ;; UTF-32 Big Endian BOM

;; for debugging parsers
;; (insta/visualize (ris-refworks-parser (slurp "resources/test/RIS/ERIC_ebsco_1232020_crl_test.txt")) :output-file "example.png" :options {:dpi 63})
;; you can't use
;; open file in Chrome as the output is large and preview has difficulty with it
;; use relatively short files, even with just 2 entries the image is large
;; note: 1. graphviz must be installed with package manager
;;          the rhizome clj dependency is in profile.clj
;;       2. you can't use the following for debugging
;;          (declare ris-refworks-parser)
;;          (defparser ris-refworks-parser
;;            (slurp "src/bnf/ris_ebsco.bnf"))
;;          must use the following for debugging
;;          (def ris-refworks-parser
;;            (insta/parser (slurp "src/bnf/ris_ebsco.bnf")))
;;
(defn str->ris-map
  "Given a string representing a RIS reference, return a citation hash-map
   with keyword keys"
  [s]
  (let [s (remove-boms s)
        parser-output (cond
                        (not (seq (:reason (ris-parser s))))
                        (ris-parser s)
                        (not (seq (:reason (ris-newline-parser s))))
                        (ris-newline-parser s)
                        (not (seq (:reason (ris-refworks-parser s))))
                        (ris-refworks-parser s)
                        :else
                        ;; return the last parser's reason
                        (ris-refworks-parser s))]
    (if (:reason parser-output)
      (throw (ex-info "RIS parsing failed"
                      {:parser-output parser-output
                       :string s}))
      (-> parser-output parser-output->coll first))))

(defn- group-references
  "Returns a vector of references, where each reference is
   comprised of a vector of lines."
  [lines]
  (->
   (reduce (fn [acc line]
             (cond
               (str/blank? line)
               acc

               (re-matches #"\s*ER\s*-.*" line)
               (-> (update acc (dec (count acc)) conj line)
                   (conj []))

               :else
               (update acc (dec (count acc)) conj line)))
           [[]]
           lines)
   pop))

(defn reader->ris-maps
  "Given a reader representing a RIS file, return a coll of citation hash-maps
   with keyword keys"
  [rdr]
  (->> rdr
       line-seq
       group-references
       (map (fn [lines]
              (str->ris-map (str/join "\n" (conj lines "")))))))

(defn str->ris-maps
  "Given a string representing a RIS file, return a coll of citation hash-maps
   with keyword keys"
  [s]
  (with-open [rdr (BufferedReader. (StringReader. s))]
    (reader->ris-maps rdr)))

(defn ris-map->str
  "Given a citation map with keyword keys, returns a String."
  [{:as m :keys [ER TY]}]
  (let [ks (->> m keys (remove #(#{:TY :ER} %)) sort)]
    (->>
     (concat
      ["TY  - " (first TY) "\n"]
      (mapcat
       (fn [k]
         (for [v (m k)]
           (str (name k)
                "  - " v
                "\n")))
       ks)
      ["ER  - " (first ER) "\n\n"])
     (str/join ""))))

(defn ris-maps->str
  "Given a sequence of citation maps with keyword keys, returns a String."
  [ms]
  (str/join "" (map ris-map->str ms)))

(defn titles-and-abstract [{:keys [AB BT CT ST T1 T2 T3 TI TT]}]
  (let [f (fn [[x]] (when (some-> x str/blank? not) x))]
    {:abstract (f AB)
     :primary-title (or (f T1) (f TI) (f ST) (f CT) (f TT))
     :secondary-title (or (f T2) (f BT) (f T3))}))

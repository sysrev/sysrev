(ns sysrev.ris.interface
  (:require [clojure.java.io :as io]
            [instaparse.core :as insta :refer [defparser]]
            [instaparse.transform :refer [transform]]))

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
(defn str->ris-maps
  "Given a string representing a RIS file, return a coll of citation hash-maps
   with keyword keys"
  [s]
  (let [parser-output (cond
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
                      {:parser-output parser-output}))
      (parser-output->coll parser-output))))


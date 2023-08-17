(ns sysrev.pdf-read.core
  (:require [clojure.string :as str]
            [sysrev.util-lite.interface :as ul])
  (:import (java.io File IOException)
           (java.nio.file Path)
           (org.apache.pdfbox.io RandomAccessFile)
           (org.apache.pdfbox.pdfparser PDFParser)
           (org.apache.pdfbox.pdmodel PDDocument)
           (org.apache.pdfbox.rendering ImageType PDFRenderer)
           (org.apache.pdfbox.text PDFTextStripper)))

(defmacro with-PDDocument [[name-sym ^File file] & body]
  `(with-open [raf# (RandomAccessFile. ~file "r")]
     (let [parser# (PDFParser. raf#)]
       (with-open [cos# (.getDocument parser#)
                   ~name-sym (PDDocument. cos# raf#)]
         (.parse parser#)
         ~@body))))

(defn condense-text [s]
  (-> (str/replace s "\u0000" "")
      (str/replace #"\-\n\s*" "")
      (str/replace #"\s+" " ")
      str/trim))

(defn raw-text ^String [^PDDocument doc & {:keys [sort-by-position]}]
  (-> (doto (PDFTextStripper.)
        (.setSortByPosition (boolean sort-by-position)))
      (.getText doc)))

(defn parse-text [^PDDocument doc]
  (-> (raw-text doc :sort-by-position true)
      ul/sanitize-str
      condense-text))

(defn read-text [^Path path invalid-pdf-value]
  (try
    (with-PDDocument [doc (.toFile path)]
      (parse-text doc))
    (catch IOException e
      (if (= "Error: Header doesn't contain versioninfo"
             (.getMessage e))
        invalid-pdf-value
        (throw e)))))

(defn ->image-seq [^PDDocument doc]
  (let [renderer (PDFRenderer. doc)]
    (map
     #(.renderImageWithDPI renderer % 300 ImageType/RGB)
     (range (.getNumberOfPages doc)))))

(ns sysrev.pdf-read.core
  (:import (java.io File)
           (org.apache.pdfbox.io RandomAccessFile)
           (org.apache.pdfbox.pdmodel PDDocument)
           (org.apache.pdfbox.pdfparser PDFParser)
           (org.apache.pdfbox.rendering ImageType PDFRenderer)
           (org.apache.pdfbox.text PDFTextStripper)))

(set! *warn-on-reflection* true)

(defmacro with-PDDocument [[name-sym ^File file] & body]
  `(with-open [raf# (RandomAccessFile. ~file "r")]
     (let [parser# (PDFParser. raf#)]
       (with-open [cos# (.getDocument parser#)
                   ~name-sym (PDDocument. cos# raf#)]
         (.parse parser#)
         ~@body))))

(defn get-text [^PDDocument doc {:keys [sort-by-position]}]
  (let [stripper (PDFTextStripper.)]
    (doto stripper
      (.setSortByPosition (boolean sort-by-position)))
    (.getText stripper doc)))

(defn ->image-seq [^PDDocument doc]
  (let [renderer (PDFRenderer. doc)]
    (map
     #(.renderImageWithDPI renderer % 300 ImageType/RGB)
     (range (.getNumberOfPages doc)))))

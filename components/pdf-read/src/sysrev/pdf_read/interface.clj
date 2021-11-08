(ns sysrev.pdf-read.interface
  (:require [sysrev.pdf-read.core :as core])
  (:import (java.io File)
           (org.apache.pdfbox.pdmodel PDDocument)))

(defn ->image-seq
  "Returns a lazy seq of java.awt.image.BufferedImage objects.

  The seq should be fully realized before doc is closed, or else
  no more items should be realized."
  [^PDDocument doc]
  (core/->image-seq doc))

#_:clj-kondo/ignore
(defn get-text
  "Returns the text of a PDDocument as a string.

  The default is to return the text in the order that it is listed in the PDF
  file, which may not correspond to the order that it is displayed in the
  document. Passing {:sort-by-position true} changes this at the cost of
  efficiency."
  [^PDDocument doc opts]
  (core/get-text doc opts))

(defmacro with-PDDocument
  "Open (and close) a PDDocument, binding it to name-sym."
  [[name-sym ^File file] & body]
  `(core/with-PDDocument [~name-sym ~file] ~@body))
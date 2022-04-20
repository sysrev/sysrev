(ns sysrev.pdf-read.interface
  (:require [sysrev.pdf-read.core :as core])
  (:import (java.io File)
           (java.nio.file Path)
           (org.apache.pdfbox.pdmodel PDDocument)))

(defn condense-text
  "Combines consecutive whitespace characters into single spaces.
   Removes leading and trailing whitespace.
   Removes \\-\\n\\s* sequences.
   Removes \\u0000 bytes."
  ^String [^String s]
  (core/condense-text s))

(defn ->image-seq
  "Returns a lazy seq of java.awt.image.BufferedImage objects.

  The seq should be fully realized before doc is closed, or else
  no more items should be realized."
  [^PDDocument doc]
  (core/->image-seq doc))

(defn parse-text
  "Returns the text of a PDDocument as a string."
  ^String [^PDDocument doc]
  (core/parse-text doc))

(defn read-text
  "Returns the text of the PDF file at path as a string.
   Returns invalid-pdf-value if the file can't be parsed as a PDF."
  [^Path path invalid-pdf-value]
  (core/read-text path invalid-pdf-value))

(defmacro with-PDDocument
  "Open (and close) a PDDocument, binding it to name-sym."
  [[name-sym ^File file] & body]
  `(core/with-PDDocument [~name-sym ~file] ~@body))

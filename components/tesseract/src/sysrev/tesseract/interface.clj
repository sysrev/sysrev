(ns sysrev.tesseract.interface
  (:require [sysrev.tesseract.core :as core])
  (:import (java.nio.file Path)))

(defn read-text
  "Returns a map of either {:text parsed-text} or {:ocr-text ocr-results}.
   Returns invalid-pdf-value if the file can't be parsed as a PDF.
   
   Attempts to parse the PDF's text with
   `sysrev.pdf-read.interface/parse-text`. If no text is found, runs
   Tesseract OCR."
  [^Path path & [invalid-pdf-value]]
  (core/read-text path invalid-pdf-value))

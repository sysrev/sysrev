(ns sysrev.tesseract.interface
  (:import (net.sourceforge.tess4j Tesseract)))

(defn get-tesseract [data-path]
  (doto (Tesseract.)
    (.setDatapath data-path)))

(ns sysrev.office.interface
  "Parsing of Microsoft Office file formats"
  (:require [clojure.java.io :as io])
  (:import org.apache.poi.xwpf.extractor.XWPFWordExtractor
           org.apache.poi.xwpf.usermodel.XWPFDocument))

(defn get-docx-text
  "Returns the text content of a docx document.

   x will be coerced to an InputStream by
   clojure.java.io/input-stream"
  [x]
  (with-open [is (io/input-stream x)]
    (with-open [doc (XWPFDocument. is)]
      (with-open [ex (XWPFWordExtractor. doc)]
        (.getText ex)))))

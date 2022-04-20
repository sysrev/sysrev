(ns sysrev.tesseract.core
  (:require [clojure.string :as str]
            [medley.core :as medley]
            [sysrev.pdf-read.interface :as pdf-read])
  (:import (java.awt.image BufferedImage)
           (java.io IOException)
           (java.nio.file Path)
           (net.sourceforge.tess4j Tesseract)))

(defn ^Tesseract tesseract [{:keys [data-path]}]
  (doto (Tesseract.)
    (.setDatapath data-path)
    (.setOcrEngineMode 1) ; LSTM_ONLY https://github.com/nguyenq/tess4j/blob/67180ebae7618bbfe569db86b4729aafef25b1ee/src/main/java/net/sourceforge/tess4j/ITessAPI.java#L42-L65
    (.setPageSegMode 1) ;Automatic page segmentation with orientation and script detection.
    ))

(defn read-text [^Path path invalid-pdf-value]
  (try
    (pdf-read/with-PDDocument [doc (.toFile path)]
      (let [text (pdf-read/condense-text
                  (pdf-read/parse-text doc))]
        (if-not (str/blank? text)
          {:text text}
          {:ocr-text
           (let [tess (tesseract
                       {:data-path (System/getenv "TESSDATA_PREFIX")})]
             (->> doc pdf-read/->image-seq
                  (map
                   (fn [^BufferedImage image]
                     (.doOCR tess image)))
                  (apply str)
                  pdf-read/condense-text))})))
    (catch IOException e
      (if (= "Error: Header doesn't contain versioninfo"
             (.getMessage e))
        invalid-pdf-value
        (throw e)))))

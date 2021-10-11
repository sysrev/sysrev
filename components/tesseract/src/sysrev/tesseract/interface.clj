(ns sysrev.tesseract.interface
  (:import (net.sourceforge.tess4j Tesseract)))

(defn ^Tesseract tesseract [{:keys [data-path]}]
  (doto (Tesseract.)
    (.setDatapath data-path)
    (.setOcrEngineMode 1) ; LSTM_ONLY https://github.com/nguyenq/tess4j/blob/67180ebae7618bbfe569db86b4729aafef25b1ee/src/main/java/net/sourceforge/tess4j/ITessAPI.java#L42-L65
    (.setPageSegMode 1) ;Automatic page segmentation with orientation and script detection.
    ))

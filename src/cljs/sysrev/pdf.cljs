(ns sysrev.pdf
  (:require ;;[cljsjs.pdfjs]
            [reagent.core :as r]
            [sysrev.util :refer [random-id]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def pdfData
  (js/atob
   (str
    "JVBERi0xLjcKCjEgMCBvYmogICUgZW50cnkgcG9pbnQKPDwKICAvVHlwZSAvQ2F0YWxvZwog"
    "IC9QYWdlcyAyIDAgUgo+PgplbmRvYmoKCjIgMCBvYmoKPDwKICAvVHlwZSAvUGFnZXMKICAv"
    "TWVkaWFCb3ggWyAwIDAgMjAwIDIwMCBdCiAgL0NvdW50IDEKICAvS2lkcyBbIDMgMCBSIF0K"
    "Pj4KZW5kb2JqCgozIDAgb2JqCjw8CiAgL1R5cGUgL1BhZ2UKICAvUGFyZW50IDIgMCBSCiAg"
    "L1Jlc291cmNlcyA8PAogICAgL0ZvbnQgPDwKICAgICAgL0YxIDQgMCBSIAogICAgPj4KICA+"
    "PgogIC9Db250ZW50cyA1IDAgUgo+PgplbmRvYmoKCjQgMCBvYmoKPDwKICAvVHlwZSAvRm9u"
    "dAogIC9TdWJ0eXBlIC9UeXBlMQogIC9CYXNlRm9udCAvVGltZXMtUm9tYW4KPj4KZW5kb2Jq"
    "Cgo1IDAgb2JqICAlIHBhZ2UgY29udGVudAo8PAogIC9MZW5ndGggNDQKPj4Kc3RyZWFtCkJU"
    "CjcwIDUwIFRECi9GMSAxMiBUZgooSGVsbG8sIHdvcmxkISkgVGoKRVQKZW5kc3RyZWFtCmVu"
    "ZG9iagoKeHJlZgowIDYKMDAwMDAwMDAwMCA2NTUzNSBmIAowMDAwMDAwMDEwIDAwMDAwIG4g"
    "CjAwMDAwMDAwNzkgMDAwMDAgbiAKMDAwMDAwMDE3MyAwMDAwMCBuIAowMDAwMDAwMzAxIDAw"
    "MDAwIG4gCjAwMDAwMDAzODAgMDAwMDAgbiAKdHJhaWxlcgo8PAogIC9TaXplIDYKICAvUm9v"
    "dCAxIDAgUgo+PgpzdGFydHhyZWYKNDkyCiUlRU9G")))

(def pdfjsLib
  (doto
      (goog.object/get js/window "pdfjs-dist/build/pdf")
      ($! :GlobalWorkerOptions
          (clj->js {:workerSrc "//mozilla.github.io/pdf.js/build/pdf.worker.js"}))))

(defn PDF
  []
  (let [canvas-id (random-id)]
    (r/create-class
     {:reagent-render
      (fn []
        [:div
         [:h1 "PDF"]
         [:canvas {:id canvas-id}]])
      :component-did-mount
      (fn [this]
        (let [loadingTask ($ pdfjsLib getDocument (clj->js {:data
                                                            pdfData}))]
          ($ loadingTask then
             (fn [pdf]
               ($ js/console log "PDF Loaded")
               (let [pageNumber 1]
                 ($ ($ pdf getPage pageNumber)
                    then
                    (fn [page]
                      ($ js/console log "page loaded")
                      (let [scale 1.5
                            viewport ($ page getViewport scale)
                            canvas ($ js/document getElementById
                                      canvas-id)
                            context ($ canvas getContext "2d")
                            _ ($! canvas :height ($ viewport :height))
                            _ ($! canvas :width ($ viewport :width))
                            renderContext (clj->js {:canvasContext
                                                    context
                                                    :viewport viewport})
                            renderTask ($ page render renderContext)]
                        ($ renderTask then
                           (fn []
                             ($ js/console log "Page Rendered")))))
                    (fn [reason]
                      ($ js/console error reason)))))))
        )})))

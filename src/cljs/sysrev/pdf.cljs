(ns sysrev.pdf
  (:require ;;[cljsjs.pdfjs]
            [reagent.core :as r]
            [sysrev.util :refer [random-id]])
  (:require-macros [reagent.interop :refer [$ $!]]))

;;https://www.nlm.nih.gov/pubs/factsheets/dif_med_pub.html
;; 24 Million for NLM
;; 27 Million for PubMed
;; "PMC serves as a digital counterpart to the NLM extensive print journal collection"
;; yet PMC returns more results for search terms then
;; PMC results have PMIDs, but not vicea versa
;; in our database, we have 137298 total articles
;; of those, 37287 contains text like '%pmc%'. 27% of articles

;; search by pmid with PMC database: <pmid>[pmid]

;; search example for: (("prostatic neoplasms"[MeSH Terms] OR ("prostatic"[All Fields] AND "neoplasms"[All Fields]) OR "prostatic neoplasms"[All Fields] OR ("prostate"[All Fields] AND "cancer"[All Fields]) OR "prostate cancer"[All Fields]) AND ("lycopene"[Supplementary Concept] OR "lycopene"[All Fields])) AND ("2005/01/01"[PDAT] : "3000/12/31"[PDAT])
;; first hit, sorted by data "Enhancement of the catalytic..."
;; actually, PMC searches are overly greedy
;;
;;(def pdfurl "https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5661393/pdf/ol-14-05-6129.pdf")
(def pdfurl "http://localhost:4061/api/test-pdf")

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
        (let [loadingTask ($ pdfjsLib getDocument #_(clj->js {:data
                                                              pdfData})
                             pdfurl)]
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

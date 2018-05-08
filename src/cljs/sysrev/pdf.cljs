(ns sysrev.pdf
  (:require ;;[cljsjs.pdfjs]
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer [dispatch]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.util :refer [random-id]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def state (r/atom nil))

(def-data :pdf/open-access-available?
  :loaded? (fn [_ article-id _]
             (not (nil? @(r/cursor state [:open-access-available? article-id]))))
  :uri (fn [article-id] (str "/api/open-access/" article-id "/availability"))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id])
  :process (fn [_ [article-id] result]
             (swap! state assoc-in [:open-access-available? article-id]
                    (:available? result))
             {}))

;; https://www.nlm.nih.gov/pubs/factsheets/dif_med_pub.html
;; 24 Million for NLM
;; 27 Million for PubMed
;; "PMC serves as a digital counterpart to the NLM extensive print journal collection"
;; yet PMC returns more results for search terms then
;; PMC results have PMIDs, but not vicea versa
;; in our database, we have 137298 total articles
;; of those, 37287 contains text like '%pmc%'. 27% of articles

;; search PubMed by PMID with PMC database: <pmid>[pmid]

(defn OpenAccessAvailable
  [article-id on-click]
  (let [available? @(r/cursor state [:open-access-available? article-id])]
    (when (nil? available?)
      (dispatch [:fetch [:pdf/open-access-available? article-id]]))
    (if available?
      [:button {:on-click on-click}  "Open Access PDF"]
      [:span {:class "empty"
              :style {:display "none"}}])))

(def pdfjsLib
  (doto
      (goog.object/get js/window "pdfjs-dist/build/pdf")
      ($! :GlobalWorkerOptions
          (clj->js {:workerSrc "//mozilla.github.io/pdf.js/build/pdf.worker.js"}))))

(defn PDF
  [article-id]
  (let [canvas-id (random-id)
        pdf-url (str "http://localhost:4061/api/open-access/" article-id
                     "/pdf")]
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
                             pdf-url)]
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
                      ($ js/console error reason))))))))})))

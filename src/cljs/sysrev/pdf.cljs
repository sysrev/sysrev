(ns sysrev.pdf
  (:require ;;[cljsjs.pdfjs]
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer [dispatch]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.util :refer [random-id]]
   [sysrev.views.upload :refer [upload-container basic-text-button]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def state (r/atom nil))

(def-data :pdf/open-access-available?
  :loaded? (fn [_ article-id _]
             (not (nil? @(r/cursor state [article-id :open-access-available?]))))
  :uri (fn [article-id] (str "/api/open-access/" article-id "/availability"))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id])
  :process (fn [_ [article-id] result]
             (swap! state assoc-in [article-id :open-access-available?]
                    (:available? result))
             {}))

(def-data :pdf/article-pdfs
  :loaded? (fn [_ article-id _]
             (constantly false))
  :uri (fn [article-id] (str "/api/files/article/" article-id "/article-pdfs"))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id])
  :process (fn [_ [article-id] result]
             (swap! state assoc-in [article-id :article-pdfs]
                    (:files result))
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
      [:div.ui.basic.button {:on-click on-click} "Open Access PDF"]
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

(defn ArticlePDFs
  [article-id]
  (dispatch [:fetch [:pdf/article-pdfs article-id]])
  [:div {:class "ui basic"}
   (doall
    (map-indexed
     (fn [i file-map]
       ^{:key (gensym i)}
       [:div {:class "content file-link "}
        [:a {:href (str "/api/files/article/" article-id "/" (:key file-map) "/" (:filename file-map))
             :target "_blank"
             :download (:filename file-map)}
         (:filename file-map)]])
     @(r/cursor state [article-id :article-pdfs])))])

(defn PDFs
  [article-id]
  [:div {:id "article-pdfs"
         :class "ui segment"}
   [:h4 {:class "ui dividing header"}
    "Article PDFs"]
   [OpenAccessAvailable article-id #(do (swap! state assoc-in [:show-pdf? article-id] (not @(r/cursor state [:show-pdf? article-id])))
                                        (.log js/console @(r/cursor state [:show-pdf? article-id])))]
   [ArticlePDFs article-id]
   [upload-container basic-text-button
    (str "/api/files/article/" article-id "/upload-pdf")
    #(dispatch [:fetch [:pdf/article-pdfs article-id]])
    "Upload PDF"]])

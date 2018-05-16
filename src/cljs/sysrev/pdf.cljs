(ns sysrev.pdf
  (:require ;;[cljsjs.pdfjs]
   [cljsjs.semantic-ui-react :as cljsjs.semantic-ui-react]
   [reagent.core :as r]
   [re-frame.core :as re-frame :refer [dispatch]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.util :refer [random-id]]
   [sysrev.views.upload :refer [upload-container basic-text-button]])
  (:require-macros [reagent.interop :refer [$ $!]]))

(def semantic-ui js/semanticUIReact)
(def Button (r/adapt-react-class (goog.object/get semantic-ui "Button")))
(def Header (r/adapt-react-class (goog.object/get semantic-ui "Header")))
(def ModalHeader (r/adapt-react-class
                  ($ (goog.object/get semantic-ui "Modal")
                     :Header)))
(def ModalContent (r/adapt-react-class
                   ($ (goog.object/get semantic-ui "Modal")
                      :Content)))
(def ModalDescription (r/adapt-react-class
                       ($ (goog.object/get semantic-ui "Modal")
                          :Description)))

(def Modal (r/adapt-react-class (goog.object/get semantic-ui "Modal")))

(defn PDFModal
  [{:keys [trigger]} child]
  [Modal {:trigger (r/as-element trigger)}
   [ModalContent child]])

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

(def-data :pdf/delete-pdf
  :loaded? (fn [_ [article-id _ _] _]
             (constantly false))
  :uri (fn [article-id key filename]
         (str "/api/files/article/" article-id "/delete/" key "/" filename))
  :prereqs (fn [] [[:identity]])
  :content (fn [article-id key filename])
  :process (fn [_ [article-id key filename] result]
             {:dispatch [:fetch [:pdf/article-pdfs article-id]]}))
;; https://www.nlm.nih.gov/pubs/factsheets/dif_med_pub.html
;; 24 Million for NLM
;; 27 Million for PubMed
;; "PMC serves as a digital counterpart to the NLM extensive print journal collection"
;; yet PMC returns more results for search terms then
;; PMC results have PMIDs, but not vicea versa
;; in our database, we have 137298 total articles
;; of those, 37287 contains text like '%pmc%'. 27% of articles

;; search PubMed by PMID with PMC database: <pmid>[pmid]

(def pdfjsLib
  (doto
      (goog.object/get js/window "pdfjs-dist/build/pdf")
      ($! :GlobalWorkerOptions
          (clj->js {:workerSrc "//mozilla.github.io/pdf.js/build/pdf.worker.js"}))))

(defn ViewPDF
  "Given a PDF URL, view it"
     [pdf-url]
     (let [canvas-id (random-id)]
       (r/create-class
        {:reagent-render
         (fn []
           [:div
            [:h1 "PDF"]
            [:canvas {:id canvas-id}]])
         :component-did-mount
         (fn [this]
           (let [loadingTask ($ pdfjsLib getDocument pdf-url)]
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

(defn view-open-access-pdf-url
  [article-id]
  (str "/api/open-access/" article-id "/view"))

(defn OpenAccessPDF
  [article-id on-click]
  (let [available? @(r/cursor state [article-id :open-access-available?])]
    (when (nil? available?)
      (dispatch [:fetch [:pdf/open-access-available? article-id]]))
    (if available?
      [:button.ui.button
       [PDFModal {:trigger [:a {:on-click #(.preventDefault %)} "Open Access PDF"]}
        [ViewPDF (view-open-access-pdf-url article-id)]]]
      [:span {:class "empty"
              :style {:display "none"}}])))


(defn view-s3-pdf-url
  [article-id key filename]
  (str "/api/files/article/" article-id  "/view/" key "/" filename))

(defn S3PDF
  [{:keys [article-id key filename]}]
  (let [confirming? (r/atom false)]
    (fn [{:keys [article-id key filename]}]
      [:div {:style {:margin-top "1em"}}
       (when-not @confirming?
         [:div.ui.buttons
          [:button.ui.button
           [PDFModal {:trigger [:a {:on-click #(.preventDefault %)}
                                    filename]}
            [ViewPDF (view-s3-pdf-url article-id key filename)]]]
          [:button.ui.button
           [:i {:class "remove icon"
                :on-click #(reset! confirming? true)}]]

          #_[:div {:class "content file-link "}
             [:a {:href (str "/api/files/article/" article-id "/" key "/" filename)
                  :target "_blank"
                  :download filename}
              filename]]])
       (when @confirming?
         [:div.ui.negative.message
          [:div.header
           (str "Are you sure you want to delete " filename "?")]
          [:br]
          [:div.ui.button {:on-click #(do (reset! confirming? false)
                                          (dispatch [:fetch [:pdf/delete-pdf article-id key filename]]))}
           "Yes"]
          [:div.ui.blue.button {:on-click #(reset! confirming? false)}
           "No"]])])))

(defn ArticlePDFs
  [article-id]
  (dispatch [:fetch [:pdf/article-pdfs article-id]])
  [:div {:class "ui basic"}
   (doall
    (map-indexed
     (fn [i file-map]
       ^{:key (gensym i)}
       [S3PDF {:article-id article-id
             :key (:key file-map)
             :filename (:filename file-map)}])
     @(r/cursor state [article-id :article-pdfs])))])

(defn PDFs
  [article-id]
  [:div {:id "article-pdfs"
         :class "ui segment"}
   [:h4 {:class "ui dividing header"}
    "Article PDFs"]
   [:div.ui.small.form
    [:div.field
     [:div.fields
      [OpenAccessPDF article-id]]]
    [:div.field
     [:div.fields
      [ArticlePDFs article-id]]]
    [:div.field
     [:div.fields
      [upload-container basic-text-button
       (str "/api/files/article/" article-id "/upload-pdf")
       #(dispatch [:fetch [:pdf/article-pdfs article-id]])
       "Upload PDF"]]]]])

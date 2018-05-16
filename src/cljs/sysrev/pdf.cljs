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

(def-data :pdf/delete-pdf
  :loaded? (fn [_ [article-id _ _] _]
             (constantly false))
  :uri (fn [article-id key filename]
         (str "/api/files/article/delete/" article-id "/" key "/" filename))
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

#_ (defn PDF
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

(defn PDF
  [{:keys [article-id key filename]}]
  (let [confirming? (r/atom false)]
    (fn [{:keys [article-id key filename]}]
      [:div
       (when-not @confirming?
         [:div.ui.right.labeled.icon.button
          [:i {:class "remove icon"
               :on-click #(reset! confirming? true)}]
          [:div {:class "content file-link "}
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
       [PDF {:article-id article-id
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
      [OpenAccessAvailable article-id #(do (swap! state assoc-in [:show-pdf? article-id] (not @(r/cursor state [:show-pdf? article-id])))
                                           (.log js/console @(r/cursor state [:show-pdf? article-id])))]]]
    [:div.field
     [:div.fields
      [ArticlePDFs article-id]]]
    [:div.field
     [:div.fields
      [upload-container basic-text-button
       (str "/api/files/article/" article-id "/upload-pdf")
       #(dispatch [:fetch [:pdf/article-pdfs article-id]])
       "Upload PDF"]]]]])

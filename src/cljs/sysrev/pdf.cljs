(ns sysrev.pdf
  (:require [re-frame.core :refer [dispatch subscribe]]
            ["react-pdf" :refer [Document Page] :as react-pdf]
            [reagent.core :as r]
            [sysrev.action.core :refer [def-action]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.macros :refer-macros [with-loader]]
            [sysrev.state.article :as article]
            [sysrev.util :as util :refer [css wrap-user-event]]
            [sysrev.views.components.core :refer [UploadButton]]
            [sysrev.views.components.list-pager :refer [ListPager]]
            [sysrev.views.semantic :refer [Checkbox]]))

;;; `npm install react-pdf` will install the version of pdfjs-dist that it requires
;;; - check in node_modules/pdfjs-dist/package.json to set this
#_(let [pdfjs-dist-version "2.5.207"]
    (set! pdfjs/GlobalWorkerOptions.workerSrc
          (util/format "https://unpkg.com/pdfjs-dist@%s/build/pdf.worker.min.js"
                       pdfjs-dist-version)))

;;; this should exist in resources/public/js/
(set! react-pdf/pdfjs.GlobalWorkerOptions.workerSrc
      "/js/pdfjs-dist/build/pdf.worker.min.js")

(defn pdf-url->key
  "Given a pdf url, extract the key from it, if it is provided, nil otherwise"
  [pdf-url]
  (nth (re-find #"/api/files/.*/article/(\d+)/view/(.*)/.*" pdf-url) 2))

(def-data :pdf/article-pdfs
  :loaded? (fn [db _project-id article-id]
             (-> (article/get-article db article-id)
                 (contains? :pdfs)))
  :uri (fn [project-id article-id]
         (str "/api/files/" project-id "/article/" article-id "/article-pdfs"))
  :prereqs (fn [project-id article-id]
             [[:article project-id article-id]])
  :process (fn [{:keys [db]} [_ article-id] {:keys [files]}]
             {:db (article/update-article db article-id {:pdfs files})}))

(def-action :pdf/delete-pdf
  :uri (fn [project-id article-id key _filename]
         (str "/api/files/" project-id "/article/" article-id "/delete/" key))
  :process (fn [_ [project-id article-id _ _] _]
             {:dispatch [:reload [:pdf/article-pdfs project-id article-id]]}))

;; search PubMed by PMID with PMC database: <pmid>[pmid]
;;
;; based on various examples provided by the authors of pdf.js
;; see: http://mozilla.github.io/pdf.js/examples/index.html#interactive-examples
;;      https://github.com/mozilla/pdf.js/tree/master/examples/components
;;      https://github.com/mozilla/pdf.js/blob/master/examples/components/simpleviewer.js
;;      https://github.com/mozilla/pdf.js/blob/master/examples/components/pageviewer.js
;; see also:
;;      https://github.com/vivin/pdfjs-text-selection-demo/blob/master/js/minimal.js
;;
;; this was ultimately based off of
;; https://github.com/mozilla/pdf.js/blob/master/examples/components/pageviewer.js

(defn view-s3-pdf-url [project-id article-id key _filename]
  (str "/api/files/" project-id "/article/" article-id "/view/" key))

(defn ArticlePdfEntryS3 [{:keys [article-id key filename]}]
  (let [confirming? (r/atom false)]
    (fn [{:keys [article-id key filename]}]
      (let [project-id @(subscribe [:active-project-id])]
        [:div
         (when-not @confirming?
           [:div
            [:a.ui.labeled.icon.button
             {:href (str "/api/files/" project-id "/article/" article-id "/download/" key)
              :target "_blank"
              :download filename}
             [:i.download.icon]
             filename]
            [:button.ui.icon.button
             {:on-click (wrap-user-event #(reset! confirming? true))}
             [:i.times.icon]]])
         (when @confirming?
           [:div.ui.negative.message.delete-pdf
            [:div.header
             (str "Are you sure you want to delete " filename "?")]
            [:div.ui.two.column.grid
             [:div.column
              [:div.ui.fluid.button
               {:on-click
                (wrap-user-event
                 #(do (reset! confirming? false)
                      (dispatch [:action [:pdf/delete-pdf
                                          project-id article-id key filename]])))}
               "Yes"]]
             [:div.column
              [:div.ui.fluid.blue.button
               {:on-click (wrap-user-event #(reset! confirming? false))}
               "No"]]]])]))))

(defn ArticlePdfListS3 [article-id]
  (when-let [pdfs (seq @(subscribe [:article/pdfs article-id]))]
    [:div
     (doall (map-indexed
             (fn [i file-map] ^{:key i}
               [:div.field>div.fields>div
                [ArticlePdfEntryS3 {:article-id article-id
                                    :key (:key file-map)
                                    :filename (:filename file-map)}]])
             pdfs))]))

(defn ArticlePdfListFull [article-id]
  (when article-id
    (when-let [project-id @(subscribe [:active-project-id])]
      (with-loader [[:article project-id article-id]
                    [:pdf/article-pdfs project-id article-id]] {}
        (let [project-id @(subscribe [:active-project-id])
              full-size? (util/full-size?)
              upload-form (fn []
                            [:div.field>div.fields
                             [UploadButton
                              (str "/api/files/" project-id
                                   "/article/" article-id "/upload-pdf")
                              #(dispatch [:reload [:pdf/article-pdfs project-id article-id]])
                              "Upload PDF"
                              nil {:margin 0}]])
              logged-in? @(subscribe [:self/logged-in?])
              member? @(subscribe [:self/member?])
              authorized? (and logged-in? member?)]
          (when authorized?
            [:div#article-pdfs.ui.segment>div.ui.grid>div.row
             [:div.left.aligned
              {:class (css [full-size? "twelve" :else "eleven"] "wide column")}
              [:div {:class (css "ui" [full-size? "small" :else "tiny"] "form")}
               ;; need better permissions for PDFs, for now, simple don't allow
               ;; people who aren't logged in to view PDFs
               [ArticlePdfListS3 article-id]]]
             [:div.right.aligned
              {:class (css [full-size? "four" :else "five"] "wide column")}
              [upload-form]]]))))))

;; `npm install react-pdf` will install the version of pdfjs that it requires, check in node_modules/pdfjs-dist/package.json to set this

(def RDocument (r/adapt-react-class Document))
(def RPage (r/adapt-react-class Page))

;; see: https://github.com/wojtekmaj/react-pdf/issues/353
;; note: This component does cause errors to occur in the
;; console during dev. however, they do not impact the
;; proper rendering of the pdf in the browser as the
;; react-pdf components a redundancy built in that
;; handles errors.
;;
;; In the above issue, the author notes that this
;; should be fixed in later releases.

(defonce checked? (r/atom false))

(defn PDFPage [{:keys [page-number page-number-preload num-pages width]}]
  (when @num-pages
    (letfn [(pager [& [_]]
              (when-not @checked?
                [:div {:style {:float "right"}}
                 [ListPager
                  {:panel @(subscribe [:active-panel])
                   :instance-key [:pdf-page]
                   :offset (dec @page-number)
                   :total-count @num-pages
                   :items-per-page 1
                   :item-name-string "pages"
                   :set-offset (fn [offset]
                                 (reset! page-number-preload (inc offset))
                                 (js/setTimeout #(do (reset! page-number (inc offset))
                                                     (reset! page-number-preload nil))
                                                25))
                   :show-message? false}]]
                #_[Pagination
                   (merge {:style (merge {:float "right"} (:style props))
                           :total-pages @num-pages
                           :active-page @page-number
                           :on-page-change (fn [_ data]
                                             (reset! page-number (.-activePage data)))}
                          (dissoc props :style))]))
            (single-page-checkbox [& [props]]
              [Checkbox {:label "Single Page" :as "h4" :toggle true
                         :style (merge {} (:style props))
                         :checked @checked?
                         :on-change #(swap! checked? not)}])]
      [:div.pdf-page-container
       [:div.pdf-top-toolbar {:style {:padding "0px, auto"}}
        [single-page-checkbox {:style {:margin-bottom "0.75rem"}}]
        [pager]]
       [:div {:style {:clear "both"}}]
       [:div.pdf-page
        (if @checked?
          (doall (for [i (range 1 (inc @num-pages))] ^{:key (str "page-" i)}
                      [RPage {:pageNumber i :width @width}]))
          (let [n @page-number
                total @num-pages]
            (when (and (integer? n) (integer? total))
              ;; render hidden RPage elements to preload content
              (doall (for [i (->> [@page-number-preload 1 (dec n) n (inc n) total]
                                  (filter integer?)
                                  distinct)]
                       (when (<= 1 i total) ^{:key (str "page-" i)}
                             [:div {:class (css [(not= i n) "no-display"])
                                    :style {:margin-top "0"
                                            :margin-bottom "1em"}}
                              [RPage {:pageNumber i :width @width}]]))))))]
       [:div.pdf-bottom-toolbar
        [single-page-checkbox {:style {:margin-top "0.75rem"}}]
        [pager]]
       [:div {:style {:clear "both"}}]])))

(defn ViewBase64PDF [{:keys [content]}]
  (let [dom-id (str "pdf-view-" (util/random-id))
        get-content-data (memoize util/base64->uint8)
        width (r/atom nil)
        num-pages (r/atom nil)
        page-number (r/atom nil)
        page-number-preload (r/atom nil)]
    (r/create-class
     {:reagent-render
      (fn [{:keys [content]}]
        [:div.pdf-view {:id dom-id}
         [RDocument {:file {:data (get-content-data content)}
                     :on-load-success (fn [pdf]
                                        (reset! num-pages (.-numPages pdf))
                                        (reset! page-number 1))}
          [PDFPage {:page-number page-number
                    :page-number-preload page-number-preload
                    :num-pages num-pages
                    :width width}]]])
      :component-did-mount
      (fn [_]
        (reset! width (-> (js/getElementById dom-id) .-width)))
      :component-did-update
      (fn [this [_ old-props]]
        (when (not= (:content (r/props this)) (:content old-props))
          (reset! page-number 1)))})))

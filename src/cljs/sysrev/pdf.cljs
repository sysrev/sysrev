(ns sysrev.pdf
  (:require ["jquery" :as $]
            ["pdfjs-dist" :as pdfjs]
            ["react-pdf" :refer [Document Page]]
            [reagent.core :as r]
            [re-frame.core :refer
             [subscribe dispatch reg-sub reg-event-db trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.action.core :refer [def-action]]
            [sysrev.state.ui :as ui-state]
            [sysrev.state.article :as article]
            [sysrev.views.components.core :refer [UploadButton]]
            [sysrev.views.semantic :refer [Checkbox Pagination]]
            [sysrev.util :as util :refer [wrap-user-event parse-integer css]]
            [sysrev.macros :refer-macros [with-loader]]))

;;; `npm install react-pdf` will install the version of pdfjs-dist that it requires
;;; - check in node_modules/pdfjs-dist/package.json to set this
#_ (let [pdfjs-dist-version "2.5.207"]
     (set! pdfjs/GlobalWorkerOptions.workerSrc
           (util/format "https://unpkg.com/pdfjs-dist@%s/build/pdf.worker.min.js"
                        pdfjs-dist-version)))

;;; this should exist in resources/public/js/
(set! pdfjs/GlobalWorkerOptions.workerSrc
      "/js/pdfjs-dist/build/pdf.worker.min.js")

(def view :pdf)

(defn pdf-url-open-access?
  "Given a pdf-url, is it an open access pdf?"
  [pdf-url]
  (boolean (re-matches #"/api/open-access/(\d+)/view/.*" pdf-url)))

(defn pdf-url->article-id
  "Given a pdf-url, return the article-id"
  [pdf-url]
  (parse-integer
   (if (pdf-url-open-access? pdf-url)
     (second (re-find #"/api/open-access/(\d+)/view" pdf-url))
     (second (re-find #"/api/files/.*/article/(\d+)/view" pdf-url)))))

(defn pdf-url->key
  "Given a pdf url, extract the key from it, if it is provided, nil otherwise"
  [pdf-url]
  (if (pdf-url-open-access? pdf-url)
    (nth (re-find #"/api/open-access/(\d+)/view/(.*)" pdf-url) 2)
    (nth (re-find #"/api/files/.*/article/(\d+)/view/(.*)/.*" pdf-url) 2)))

(defn get-ann-context [pdf-url & [project-id]]
  (when pdf-url
    {:class "pdf"
     :project-id (or project-id @(subscribe [:active-project-id]))
     :article-id (pdf-url->article-id pdf-url)
     :pdf-key (pdf-url->key pdf-url)}))

(reg-sub ::pdf-cache
         (fn [db [_ url]]
           (get-in db [:data :pdf-cache url])))

(reg-event-db ::pdf-cache [trim-v]
              (fn [db [url pdf]]
                (assoc-in db [:data :pdf-cache url] pdf)))

(reg-sub ::get
         :<- [:view-field view []]
         (fn [view-state [_ context path]]
           (get-in view-state (concat [context] path))))

(reg-event-db ::set [trim-v]
              (fn [db [context path value]]
                (let [full-path (concat [context] path)]
                  (ui-state/set-view-field db view full-path value))))

(reg-sub ::page-num
         :<- [:view-field view []]
         (fn [view-state [_ context]]
           (get-in view-state (concat [context] [:page-num]) 1)))

(def-data :pdf/open-access-available?
  :loaded? (fn [db _project-id article-id]
             (-> (article/get-article db article-id)
                 (contains? :open-access-available?)))
  :uri (fn [_ article-id] (str "/api/open-access/" article-id "/availability"))
  :prereqs (fn [project-id article-id]
             [[:article project-id article-id]])
  :process (fn [{:keys [db]} [_ article-id] {:keys [available? key]}]
             {:db (article/update-article db article-id {:open-access-available? available?
                                                         :key key})}))

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

(defn view-open-access-pdf-url [article-id key]
  (str "/api/open-access/" article-id "/view/" key))

(defn ArticlePdfEntryOpenAccess [article-id]
  (when @(subscribe [:article/open-access-available? article-id])
    [:div.field>div.fields
     [:div
      [:a.ui.labeled.icon.button
       {:href (view-open-access-pdf-url
               article-id @(subscribe [:article/key article-id]))
        :target "_blank"
        :download (str article-id ".pdf")}
       [:i.download.icon]
       (str article-id ".pdf")]]]))

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
  (when-let [pdfs (seq (->> @(subscribe [:article/pdfs article-id])
                            (remove :open-access?)))]
    [:div (doall (map-indexed
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
              #_ loading? #_ #(loading/any-loading? :only :pdf/open-access-available?)
              upload-form (fn []
                            [:div.field>div.fields
                             [UploadButton
                              (str "/api/files/" project-id
                                   "/article/" article-id "/upload-pdf")
                              #(dispatch [:reload [:pdf/article-pdfs project-id article-id]])
                              "Upload PDF"
                              nil {:margin 0}]])
              open-access? @(subscribe [:article/open-access-available? article-id])
              logged-in? @(subscribe [:self/logged-in?])
              member? @(subscribe [:self/member?])
              authorized? (and logged-in? member?)]
          (dispatch [:require [:pdf/open-access-available?
                               project-id article-id]])
          (when (or authorized? open-access?)
            [:div#article-pdfs.ui.segment>div.ui.grid>div.row
             [:div.left.aligned
              {:class (css [full-size? "twelve" :else "eleven"] "wide column")}
              [:div {:class (css "ui" [full-size? "small" :else "tiny"] "form")}
               [ArticlePdfEntryOpenAccess article-id]
               ;; need better permissions for PDFs, for now, simple don't allow
               ;; people who aren't logged in to view PDFs
               (when logged-in?
                 [ArticlePdfListS3 article-id])]]
             (when logged-in?
               [:div.right.aligned
                {:class (css [full-size? "four" :else "five"] "wide column")}
                [upload-form]])]))))))

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

(defn PDFPage [{:keys [page-number num-pages width]}]
  (when @num-pages
    (letfn [(pager [& [props]]
              (when-not @checked?
                [Pagination
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
       [:div.pdf-top-toolbar {:style {:padding "0px, auto"
                                      :margin-bottom "1rem"}}
        [single-page-checkbox {:style {:margin-top "0.75rem"}}]
        [pager]
        [:div {:style {:clear "both"}}]]
       [:div.pdf-page
        (if @checked?
          (doall (for [i (range 1 (inc @num-pages))] ^{:key (str "page-" i)}
                   [RPage {:pageNumber i :width @width}]))
          [RPage {:pageNumber @page-number :width @width}])]
       [:div.pdf-bottom-toolbar
        [single-page-checkbox {:style {:margin-top "1.75rem"}}]
        [pager {:style {:margin-top "1rem"}}]
        [:div {:style {:clear "both"}}]]])))

(defn ViewBase64PDF [{:keys [content]}]
  (let [dom-id (str "pdf-view-" (util/random-id))
        get-content-data (memoize util/base64->uint8)
        width (r/atom nil)
        num-pages (r/atom nil)
        page-number (r/atom nil)]
    (r/create-class
     {:reagent-render
      (fn [{:keys [content]}]
        [:div.pdf-view {:id dom-id}
         [RDocument {:file {:data (get-content-data content)}
                     :on-load-success (fn [pdf]
                                        (reset! num-pages (.-numPages pdf))
                                        (reset! page-number 1))}
          [PDFPage {:page-number page-number
                    :num-pages num-pages
                    :width width}]]])
      :component-did-mount
      (fn [_]
        (reset! width (-> ($ (str "#" dom-id)) (.width))))
      :component-did-update
      (fn [this [_ old-props]]
        (when (not= (:content (r/props this)) (:content old-props))
          (reset! page-number 1)))})))

(defn ViewReactPDF [{:keys [url filename]}]
  (let [dom-id (str "pdf-view-" (util/random-id))
        width (r/atom nil)
        num-pages (r/atom nil)
        page-number (r/atom nil)]
    (r/create-class
     {:reagent-render
      (fn [{:keys [url filename]}]
        [:div.pdf-view {:id dom-id}
         [RDocument {:file {:url url}
                     :on-load-success (fn [pdf]
                                        (reset! num-pages (.-numPages pdf))
                                        (reset! page-number 1))}
          [PDFPage {:page-number page-number
                    :num-pages num-pages
                    :width width}]]])
      :component-did-mount
      (fn [_]
        (reset! width (-> ($ (str "#" dom-id)) (.width))))
      :component-did-update
      (fn [this [_ old-props]]
        (when (not= (:url (r/props this)) (:url old-props))
          (reset! page-number 1)))})))

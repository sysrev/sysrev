(ns sysrev.pdf
  (:require ["jquery" :as $]
            ["pdfjs-dist" :as pdfjs]
            ["react-pdf" :refer [Document Page]]
            [reagent.core :as r]))

(def pdfjs-dist-version "2.1.266")
;; ;; TODO: load this locally somehow
(set! pdfjs/GlobalWorkerOptions.workerSrc
      (str "https://unpkg.com/pdfjs-dist@" pdfjs-dist-version "/build/pdf.worker.min.js"))

;; `npm install react-pdf` will install the version of pdfjs that it requires, check in node_modules/pdfjs-dist/package.json to set this

(defn pdf-url-open-access?
  "Given a pdf-url, is it an open access pdf?"
  [pdf-url]
  (boolean (re-matches #"/api/open-access/(\d+)/view/.*" pdf-url)))

(defn pdf-url->key
  "Given a pdf url, extract the key from it, if it is provided, nil otherwise"
  [pdf-url]
  (if (pdf-url-open-access? pdf-url)
    (nth (re-find #"/api/open-access/(\d+)/view/(.*)" pdf-url) 2)
    (nth (re-find #"/api/files/.*/article/(\d+)/view/(.*)/.*" pdf-url) 2)))

(defn view-s3-pdf-url [project-id article-id key _filename]
  (str "/api/files/" project-id "/article/" article-id "/view/" key))

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

(defn ViewBase64PDF
  [{:keys [content]}]
  (let [content (r/atom content)
	container-id "pdf-view"
	width (r/atom nil)
        num-pages (r/atom nil)]
    (r/create-class
     {:render
      (fn [_]
        [:div {:id container-id}
         [RDocument {:file
                     {:data (-> @content
				js/atob
				(js/Uint8Array.from (fn [c] (.charCodeAt c 0))))}
		     :on-load-success (fn [foo]
				        (reset! num-pages (.-numPages foo)))}
	  (when-not (nil? @num-pages)
            (doall (for [i (range 1 (+ @num-pages 1))]
		     ^{:key (str "page-" i)}
		     [RPage {:pageNumber i
			     :width @width}])))]])
      :component-did-update
      (fn [this _]
        (reset! content (-> this r/props :content)))
      :component-did-mount
      (fn [_]
        (let [new-width (-> (js/document.getElementById container-id)
                            $
                            .width)]
          (reset! width new-width)))
      :component-will-umount
      (fn [_]
        (reset! num-pages nil))})))

(defn ViewPDF
  [{:keys [pdf-url]}]
  (let [pdf-url (r/atom pdf-url)
        container-id "pdf-view"
	width (r/atom nil)
        num-pages (r/atom nil)]
    (r/create-class
     {:render
      (fn [_]
        [:div {:id container-id}
         [RDocument {:file @pdf-url
		     :on-load-success (fn [foo]
				        (reset! num-pages (.-numPages foo)))}
	  (when-not (nil? @num-pages)
            (doall (for [i (range 1 (+ @num-pages 1))]
		     ^{:key (str "page-" i)}
		     [RPage {:pageNumber i
			     :width @width}])))]])
      :component-did-update
      (fn [this _]
        (reset! pdf-url (-> this r/props :pdf-url)))
      :component-did-mount
      (fn [_]
        (let [new-width (-> (js/document.getElementById container-id)
                            $
                            .width)]
          (reset! num-pages nil)
          (reset! width new-width)))
      :component-will-umount
      (fn [_]
        (reset! num-pages nil))})))

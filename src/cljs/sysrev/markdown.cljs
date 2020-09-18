(ns sysrev.markdown
  (:require [clojure.string :as str]
            ["showdown" :as showdown]
            ["dompurify" :as DOMPurify]
            [clojure.spec.alpha :as s]
            [goog.dom :as gdom]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [sysrev.views.semantic :refer [TextArea]]
            [sysrev.util :as util :refer [css]]))

;; security, particularly regarding XSS attacks is a big concern when letting
;; users generate their own HTML
;; see: https://github.com/showdownjs/showdown/wiki/Markdown's-XSS-Vulnerability-(and-how-to-mitigate-it)
;; unfortunately, there is little in the way in regards to mitigation at that article
;;
;; Some example of md that could cause issues:
;; [some text](javascript:alert('xss'))
;;
;; > hello <a name="n"
;; > href="javascript:alert('xss')">*you*</a>
;;
;; This currently uses the https://github.com/cure53/DOMPurify
;;
;; This issue https://github.com/markedjs/marked/issues/1232 shows additional filters
;; dompurify: https://www.npmjs.com/package/dompurify
;; sanitize-html: https://www.npmjs.com/package/sanitize-html
;; insane: https://www.npmjs.com/package/insane
;; marked-sanitizer-github: https://www.npmjs.com/package/marked-sanitizer-github

(defn create-markdown-html [markdown]
  (let [converter (showdown/Converter. (clj->js {:simpleLineBreaks true}))]
    ;; this is a hook to add target=_blank to anything with a href
    (DOMPurify/addHook "afterSanitizeAttributes"
                       (fn [node] (when (.hasAttribute node "href")
                                    (.setAttribute node "target" "_blank"))))
    (->> markdown (.makeHtml converter) (DOMPurify/sanitize))))

(defn html->string [s]
  (let [div-container (js/document.createElement "div")]
    (set! (.-innerHTML div-container) s)
    (gdom/getTextContent div-container)))

(defn RenderMarkdown [markdown]
  [:div {:class "markdown-content"
         :style {:word-wrap "break-word"}
         :dangerouslySetInnerHTML {:__html (create-markdown-html markdown)}}])

(s/def ::ratom #(or (instance? ratom/RAtom %)
                    (instance? ratom/RCursor %)))

(s/def ::content string?)
(s/def ::set-content! fn?)
(s/def ::loading? (s/nilable boolean?))
(s/def ::mutable? (s/nilable boolean?))
(s/def ::editing? ::ratom)

#_(s/fdef MarkdownComponent
    :args (s/keys :req-un [::content ::set-content! ::loading? ::mutable? ::editing?]))

;; refactor to use semantic js components to make it easier to read
(defn MarkdownComponent
  "Component for displaying and editing markdown. set-content! takes one
  string argument and is responsible for resetting the editing? atom
  to false after saving value."
  [{:keys [content set-content! loading? mutable? editing?]}]
  (let [draft-content (r/atom nil)
        mobile? (util/mobile?)]
    (fn [{:keys [content set-content! loading? mutable? editing?]}]
      (if @editing?
        (let [active-content (or @draft-content content "")
              changed? (not= (or active-content "") (or content ""))]
          [:div.markdown-component
           [:div.ui.segments.markdown-editor
            [:div.ui.form.secondary.segment
             ;; TODO: :autoHeight option is no longer supported -
             ;;       semantic-ui-react suggests replacement:
             ;;       https://www.npmjs.com/package/react-textarea-autosize
             [TextArea {:fluid "true"
                        ;; :autoHeight true
                        :style {:min-height "12em"}
                        :disabled (boolean loading?)
                        :placeholder "Enter a Markdown description"
                        :on-change (util/on-event-value #(reset! draft-content %))
                        :default-value active-content}]
             [:div.ui.middle.aligned.two.column.grid.form-buttons
              {:style {:margin-top "0"}}
              [:div.left.aligned.column
               [:a.markdown-link.bold
                {:href "https://github.com/adam-p/markdown-here/wiki/Markdown-Cheatsheet"
                 :target "_blank" :rel "noopener noreferrer"}
                "Markdown Cheatsheet"]]
              [:div.right.aligned.column.form-buttons
               [:button.ui.tiny.positive.button.save-button
                {:class (css [(not changed?) "disabled"]
                             [loading? "loading"]
                             [(not mobile?) "icon labeled"])
                 :on-click #(set-content! @draft-content)}
                (when (not mobile?) [:i.circle.check.icon])
                "Save"]
               [:button.ui.tiny.button.cancel-button
                {:on-click #(do (reset! editing? false)
                                (reset! draft-content nil))
                 :class (css [loading? "disabled"]
                             [(not mobile?) "icon labeled"])
                 :style {:margin-right "0"}}
                (when (not mobile?) [:i.times.icon])
                "Cancel"]]]]]
           [:div.ui.segments.markdown-preview
            [:div.ui.secondary.header.segment>h5.ui.header "Preview"]
            [:div.ui.secondary.segment [RenderMarkdown active-content]]]])
        (when-not (str/blank? content)
          [:div.markdown-component [RenderMarkdown content]])))))

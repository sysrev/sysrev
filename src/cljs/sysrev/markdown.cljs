(ns sysrev.markdown
  (:require [clojure.string :as str]
            [cljsjs.showdown]
            [clojure.spec.alpha :as s]
            [reagent.core :as r]
            [sysrev.views.semantic :refer [Segment TextArea]]
            [sysrev.shared.util :as sutil :refer [css]])
  (:require-macros [reagent.interop :refer [$]]))

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
  (let [converter (js/showdown.Converter. (clj->js {:simpleLineBreaks true}))]
    ;; this is a hook to add target=_blank to anything with a href
    ($ js/DOMPurify addHook "afterSanitizeAttributes"
       (fn [node] (when ($ node hasAttribute "href")
                    ($ node setAttribute "target" "_blank"))))
    (->> markdown ($ converter makeHtml) ($ js/DOMPurify sanitize))))

(defn RenderMarkdown [markdown]
  [:div {:style {:word-wrap "break-word"}
         :dangerouslySetInnerHTML {:__html (create-markdown-html markdown)}}])

(s/def ::ratom #(or (instance? reagent.ratom/RAtom %)
                    (instance? reagent.ratom/RCursor %)))

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
  (let [draft-content (r/atom nil)]
    (fn [{:keys [content set-content! loading? mutable? editing?]}]
      [:div.markdown-component
       (if @editing?
         (let [active-content (or @draft-content content "")
               changed? (not= (or active-content "") (or content ""))]
           [:div
            [:div.ui.segments
             [:div.ui.form.secondary.segment
              [TextArea {:fluid "true"
                         :autoHeight true
                         :disabled (boolean loading?)
                         :placeholder "Enter a Markdown description"
                         :on-change #(reset! draft-content (-> ($ % :target) ($ :value)))
                         :default-value active-content}]]
             [:div.ui.secondary.middle.aligned.two.column.grid.segment
              [:div.left.aligned.column
               [:a.markdown-link
                {:href "https://github.com/adam-p/markdown-here/wiki/Markdown-Cheatsheet"
                 :target "_blank" :rel "noopener noreferrer"}
                "Markdown Cheatsheet"]]
              [:div.right.aligned.column.form-buttons
               [:button.ui.tiny.positive.icon.labeled.button
                {:class (css [(not changed?) "disabled"] [loading? "loading"])
                 :on-click #(set-content! @draft-content)}
                [:i.circle.check.icon] "Save"]
               [:button.ui.tiny.icon.labeled.button
                {:on-click #(do (reset! editing? false)
                                (reset! draft-content nil))
                 :class (css [loading? "disabled"])
                 :style {:margin-right "0"}}
                [:i.times.icon] "Cancel"]]]]
            [:div.ui.segments
             [:div.ui.secondary.header.segment>h5.ui.header "Preview"]
             [:div.ui.secondary.segment [RenderMarkdown active-content]]]])
         (when-not (str/blank? content)
           [:div [RenderMarkdown content]]))])))

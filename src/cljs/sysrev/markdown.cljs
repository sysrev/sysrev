(ns sysrev.markdown
  (:require [clojure.string :as str]
            [cljsjs.showdown]
            [clojure.spec.alpha :as s]
            [reagent.core :as r]
            [sysrev.views.semantic :refer [Segment TextArea]])
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
  (let [converter (js/showdown.Converter.
                   (clj->js {:simpleLineBreaks true}))]
    ;; this is a hook to add target=_blank to anything with a href
    ($ js/DOMPurify addHook
       "afterSanitizeAttributes"
       (fn [node]
         (when ($ node hasAttribute "href")
           ($ node setAttribute "target" "_blank"))))
    (->> markdown
         ($ converter makeHtml)
         ($ js/DOMPurify sanitize))))

(defn RenderMarkdown
  [markdown]
  [:div {:style {:word-wrap "break-word"}
         :dangerouslySetInnerHTML
         {:__html (create-markdown-html markdown)}}])

(s/def ::ratom #(instance? reagent.ratom/RAtom %))
(s/def ::markdown ::ratom)
(s/def ::loading? #(fn? %))
(s/def ::editing? ::ratom)
(s/def ::set-markdown! #(fn? %))
(s/def ::mutable? boolean?)
(s/def ::draft-markdown string?)

(s/fdef MarkdownComponent
  :args (s/keys :req-un [::markdown ::set-markdown! ::loading? ::mutable? ::editing?]))

;; refactor to use semantic js components to make it easier to read
(defn MarkdownComponent
  "Return a component for displaying and editing markdown. set-markdown! is a function which takes a string and must handle the editing? atom"
  [{:keys [markdown set-markdown! loading? mutable? editing?]}]
  (let [draft-markdown (r/atom "")]
    (r/create-class
     {:reagent-render
      (fn [this]
        [:div.markdown-component
         (if @editing?
           [:div
            [:div.ui.segments
             [:div.ui.form.secondary.segment
              [TextArea {:fluid "true"
                         :autoHeight true
                         :disabled (loading?)
                         :placeholder "Enter a Markdown description"
                         :on-change #(reset! draft-markdown
                                             (-> ($ % :target) ($ :value)))
                         :default-value (or @draft-markdown "")}]]
             [:div.ui.secondary.middle.aligned.grid.segment
              [:div.eight.wide.left.aligned.column
               [:a.markdown-link
                {:href "https://github.com/adam-p/markdown-here/wiki/Markdown-Cheatsheet"
                 :target "_blank"
                 :rel "noopener noreferrer"}
                "Markdown Cheatsheet"]]
              [:div.eight.wide.right.aligned.column.form-buttons
               [:button.ui.tiny.positive.icon.labeled.button
                {:class (cond-> ""
                          (not (not= (or @markdown "")
                                     (or @draft-markdown ""))) (str " disabled")
                          (loading?)       (str " loading"))
                 :on-click #(set-markdown! @draft-markdown)}
                [:i.circle.check.icon]
                "Save"]
               [:button.ui.tiny.icon.labeled.button
                {:on-click #(reset! editing? false)
                 :class (when (loading?) "disabled")
                 :style {:margin-right "0"}}
                [:i.times.icon]
                "Cancel"]]]]
            [:div.ui.segments
             [:div.ui.secondary.header.segment
              [:h5.ui.header "Preview"]]
             [:div.ui.secondary.segment
              [RenderMarkdown @draft-markdown]]]]
           (when-not (str/blank? @markdown)
             [:div [RenderMarkdown @markdown]]))])
      :component-did-mount
      (fn [this]
        (reset! draft-markdown (or @markdown "")))
      :component-will-receive-props
      (fn [this new-argv]
        (reset! draft-markdown (or @(-> new-argv second :markdown) "")))})))


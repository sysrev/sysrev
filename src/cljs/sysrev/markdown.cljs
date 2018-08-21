(ns sysrev.markdown
  (:require [cljsjs.semantic-ui-react]
            [cljsjs.showdown]
            [reagent.core :as r])
  (:require-macros [reagent.interop :refer [$]]))


(def default-state {:editing? false
                    :markdown-paragraph ""
                    :editing-paragraph ""})
(def state (r/atom default-state))

(def semantic-ui js/semanticUIReact)
(def TextArea (r/adapt-react-class (goog.object/get semantic-ui "TextArea")))

;; security, particularly regarding XSS attacks is a big concern when letting
;; users generate their own HTML
;; see: https://github.com/showdownjs/showdown/wiki/Markdown's-XSS-Vulnerability-(and-how-to-mitigate-it)
;; unfortunately, there is little in the way in regards to mitigation at that article
;;
;; Some example of md that could cause issues:
;; "[some text](javascript:alert('xss'))"
;; a particularly nasty one:
;; "> hello <a name=\"n\"\n> href=\"javascript:alert('xss')\">*you*</a>"
;;
;; This currently uses the https://github.com/cure53/DOMPurify
;;
;; This issue https://github.com/markedjs/marked/issues/1232 shows additional filters
;; dompurify: https://www.npmjs.com/package/dompurify
;; sanitize-html: https://www.npmjs.com/package/sanitize-html
;; insane: https://www.npmjs.com/package/insane
;; marked-sanitizer-github: https://www.npmjs.com/package/marked-sanitizer-github

(defn RenderMarkdown
  [markdown]
  (let [converter (doto (js/showdown.Converter.)
                    ($ setOption "simpleLineBreaks" true))]
    [:div {:dangerouslySetInnerHTML {:__html (->> markdown
                                                  ($ converter makeHtml)
                                                  ($ js/DOMPurify sanitize))}}]))
(defn MarkdownComponent
  [state]
  (let [editing? (r/cursor state [:editing?])
        markdown-paragraph (r/cursor state [:markdown-paragraph])
        editing-paragraph (r/cursor state [:editing-paragraph])]
    [:div.ui.panel
     [:div.ui.two.column.middle.aligned.grid
      [:div.ui.left.aligned.column
       (when (clojure.string/blank? @markdown-paragraph)
         [:h4 "Project Description"])]
      [:div.ui.right.aligned.column
       [:div {:on-click (fn [event]
                          (if @editing?
                            (reset! markdown-paragraph @editing-paragraph)
                            (reset! editing-paragraph @markdown-paragraph))
                          (reset! editing? (not @editing?)))
              :class "ui small icon button"}
        (if @editing?
          [:p "Save"]
          [:i {:class "ui blue pencil horizontal icon"}])]
       (when (and @editing?
                  (not (clojure.string/blank? @markdown-paragraph)))
         [:div {:on-click (fn [event]
                            (reset! editing? false))
                :class "ui small icon button"}
          [:p "Discard Changes"]])]
      (if @editing?
        [:div {:class "sixteen wide column"}
         [:form.ui.form
          [TextArea {:fluid "true"
                     :autoHeight true
                     :placeholder "Enter a Markdown description"
                     :on-change (fn [e]
                                  (let [value (-> ($ e :target)
                                                  ($ :value))]
                                    (reset! editing-paragraph
                                            (-> ($ e :target)
                                                ($ :value)))))
                     :default-value @editing-paragraph}]]
         [:div
          [:h4 "Preview" [RenderMarkdown @editing-paragraph]]]]
        [:div [RenderMarkdown @markdown-paragraph]
         [:br]])
        ]]))

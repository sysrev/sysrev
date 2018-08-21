(ns sysrev.markdown
  (:require [cljsjs.showdown])
  (:require-macros [reagent.interop :refer [$]]))

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
  (let [converter (js/showdown.Converter.)]
    [:div {:dangerouslySetInnerHTML {:__html (->> markdown
                                                  ($ converter makeHtml)
                                                  ($ js/DOMPurify sanitize))}}]))

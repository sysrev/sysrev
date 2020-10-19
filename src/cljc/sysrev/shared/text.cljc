(ns sysrev.shared.text)

(def site-intro-text
  [" is a platform for collaborative extraction of data from documents."])

(defn uri-title [uri]
  (cond
    (= uri "/") (str "Built for data miners | Sysrev")
    (= uri "/lit-review") (str "Free Literature Review | Sysrev")
    (= uri "/data-extraction") (str "Advanced Data Extraction | Sysrev")
    (= uri "/systematic-review") (str "Modern Systematic Review | Sysrev")
    (= uri "/managed-review") (str "Expert Data Extraction | Sysrev")
    (= uri "/register") "Start Your Free Trial | Sysrev"
    :else (str "Sysrev" (first site-intro-text))))

(defn uri-meta-description [uri]
  (cond
    (= uri "/") (str "Sysrev" (first site-intro-text))
    (= uri "/lit-review") (str "Start reviewing literature for free. Sysrev is the most advanced systematic review solution.")
    (= uri "/data-extraction") (str "Recruit reviewers to extract data from PDFs, citation data, or text.")
    (= uri "/systematic-review") (str "Start reviewing literature for free. Sysrev is the most advanced systematic review solution")
    (= uri "/managed-review") (str "Get help from sysrev experts with massive data extraction or review projects.")
    (= uri "/register") "Try Sysrev for free.  A powerful systematic review solution for literature review, data extraction, and managed review."
    :else (str "Sysrev" (first site-intro-text)))) ;TODO - project pages should have meta tags

;TODO maybe redo the introduction video
(defn links [keyword]
  (get {
        :twitter "https://twitter.com/sysrev1"


        :blog "https://blog.sysrev.com"
        :getting-started-blog "https://blog.sysrev.com/getting-started/"
        :getting-started-topic "https://blog.sysrev.com/tag/getting-started/"
        :mangiferin-part-one "https://blog.sysrev.com/mangiferin-managed-review/"
        :mangiferin-insights "https://blog.sysrev.com/generating-insights/"

        :managed-review-landing "https://sysrev.com/managed-review"

        :youtube "https://www.youtube.com/channel/UCoUbMAvxBSZpOlqKjOkxNzQ"
        :getting-started-video "https://youtu.be/dHISlGOm7A8"
        :analytics "https://youtu.be/FgxJ4zTVUn4"
        :analytics-embed "https://www.youtube.com/embed/FgxJ4zTVUn4"

       } keyword))

(defn make-link [keyword link-text] (str "<a href='" (links keyword) "'>" link-text "</a>"))
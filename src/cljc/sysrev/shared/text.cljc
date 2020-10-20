(ns sysrev.shared.text
  (:require #?@(:clj [[sysrev.db.queries :as q]])
            [sysrev.util :as util #?@(:cljs [:refer [format]])]))

(def site-intro-text
  [" is a platform for collaborative extraction of data from documents."])

(defn uri-title [uri]
  (case uri
    ("/" "")              "Built for data miners | Sysrev"
    "/lit-review"         "Free Literature Review | Sysrev"
    "/data-extraction"    "Advanced Data Extraction | Sysrev"
    "/systematic-review"  "Modern Systematic Review | Sysrev"
    "/managed-review"     "Expert Data Extraction | Sysrev"
    "/register"           "Start Your Free Trial | Sysrev"
    ;; in cljs, project name is handled outside of this
    #?(:cljs "Sysrev"
       ;; TODO add an authentication check for private projects
       :clj (-> (when (string? uri)
                  (some-> (re-find #"/p/([0-9]+)" uri)
                          last
                          util/parse-integer
                          (q/get-project :name)
                          (str " | Sysrev")))
                (or "Sysrev")))))

(defn uri-meta-description [uri]
  (case uri
    "/lit-review"         "Start reviewing literature for free. Sysrev is the most advanced systematic review solution."
    "/data-extraction"    "Recruit reviewers to extract data from PDFs, citation data, or text."
    "/systematic-review"  "Start reviewing literature for free. Sysrev is the most advanced systematic review solution"
    "/managed-review"     "Get help from Sysrev experts with massive data extraction or review projects."
    "/register"           "Try Sysrev for free. A powerful systematic review solution for literature review, data extraction, and managed review."
    ;; TODO - project pages should have meta tags
    (str "Sysrev" (first site-intro-text))))

;; TODO maybe redo the introduction video
(defn links [k]
  (get {:sysrev.com "https://sysrev.com"
        :welcome-invite-link "https://sysrev.com/register/d7cef14c4b7a"
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
        :analytics-embed "https://www.youtube.com/embed/FgxJ4zTVUn4"} k))

(defn make-link [k link-text] (format "<a href='%s'>%s</a>" (links k) link-text))

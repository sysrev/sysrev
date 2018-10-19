(ns sysrev.test.browser.blog
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [sysrev.test.core :as test :refer [default-fixture]]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.web.blog :as blog]))

(use-fixtures :once default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(deftest-browser blog-pages
  (let [{:keys [blog-url]} (test/get-selenium-config)
        entry-link ".ui.segment.blog-list-entry h3 a"
        list-button (xpath "//a[contains(@class,'button')"
                           " and contains(text(),'All Blog Entries')]")]
    (when blog-url
      (if (test/db-connected?)
        (let [entry {:url "https://s3.amazonaws.com/sysrev-blog/browser-test.html"
                     :title "Browser Test Entry"
                     :description "Test entry for [SysRev](http://sysrev.com/) blog."}]
          (try
            (blog/add-blog-entry entry)
            (log/info "added blog entry")
            (catch Throwable e
              (log/warn "blog entry not added")))
          (log/info "loading blog entries list"
                    (format "(%s)" blog-url))
          (taxi/to blog-url)
          (is (b/exists? "#blog_list"))
          (is (b/exists? entry-link))
          (log/info "navigating to blog entry page")
          (b/click (xpath (format "//a[text()='%s']" (:title entry))) )
          (is (b/exists? list-button))
          (is (b/exists? "iframe")))
        (do (log/info "loading blog entries list"
                      (format "(%s)" blog-url))
            (taxi/to blog-url)
            (is (b/exists? "#blog_list"))
            (b/wait-until-loading-completes :pre-wait 200)
            (Thread/sleep 100)
            (if (b/exists? entry-link :wait? false)
              (do (log/info "navigating to blog entry page")
                  (b/click entry-link)
                  (is (b/exists? list-button))
                  (is (b/exists? "iframe")))
              (do (log/info "no blog entries shown"))))))))

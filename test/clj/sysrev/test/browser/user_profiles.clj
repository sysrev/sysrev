(ns sysrev.test.browser.user-profiles
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clj-webdriver.core :refer [->actions move-to-element click-and-hold move-by-offset
                                        release perform]]
            [clj-webdriver.taxi :as taxi]
            [clojure.data.json :as json]
            [clojure.test :refer :all]
            [sysrev.api :as api]
            [sysrev.db.core :refer [with-transaction]]
            [sysrev.db.files :as files]
            [sysrev.filestore :as fstore]
            [sysrev.db.groups :as groups]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.test.browser.annotator :as annotator]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.markdown :as markdown]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.review-articles :as ra]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [in? parse-integer]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def user-name-link (xpath "//a[@id='user-name-link']"))
(def user-profile-tab (xpath "//a[@id='user-profile']"))
(def activity-values (xpath "//h2[contains(@class,'articles-reviewed' | 'labels-contributed' | 'annotations-contributed')]"))
(def user-activity-summary-div (xpath "//div[@id='user-activity-summary']" activity-values))
(defn project-activity-summary-div [project-name]
  (xpath "//a[contains(text(),'" project-name "')]"
         "/ancestor::div[contains(@id,'project-')]"
         activity-values))
(def edit-introduction (xpath "//a[@id='edit-introduction']"))

;; avatar
;; (def avatar (xpath "//img[contains(@src,'avatar')]"))
(def avatar (xpath "//div[@data-tooltip='Change Your Avatar']"))
(def upload-button (xpath "//button[contains(text(),'Upload Profile Image')]"))

(def image-base64
  (->> "test-files/demo-1.jpg" io/resource io/file util/slurp-bytes util/bytes->base64))

;; http://blog.fermium.io/how-to-send-files-to-a-dropzone-js-element-in-selenium/
(def upload-image-blob-js
  (str "var myZone, blob, base64Image; myZone = Dropzone.forElement('.dropzone');"
       "base64Image = '" image-base64 "';"
       "function base64toBlob(r,e,n){e=e||\"\",n=n||512;for(var t=atob(r),a=[],o=0;o<t.length;o+=n){for(var l=t.slice(o,o+n),h=new Array(l.length),b=0;b<l.length;b++)h[b]=l.charCodeAt(b);var v=new Uint8Array(h);a.push(v)}var c=new Blob(a,{type:e});return c}"
       "blob = base64toBlob(base64Image, 'image / png');"
       "blob.name = 'testfile.png';"
       "myZone.addFile(blob);"))

(defn private-project-names []
  (b/get-elements-text (xpath "//div[@id='private-projects']/div[contains(@id,'project-')]/a")))

(defn public-project-names []
  (b/get-elements-text (xpath "//div[@id='public-projects']/div[contains(@id,'project-')]/a")))

(defn user-activity-summary []
  (b/wait-until-displayed user-activity-summary-div)
  (select-keys
   (->> (taxi/elements user-activity-summary-div)
        (mapv #(hash-map (keyword (taxi/attribute % :class))
                         (parse-integer (taxi/text %))))
        (apply merge))
   [:articles-reviewed :labels-contributed :annotations-contributed]))

(defn project-activity-summary [project-name]
  (let [q (project-activity-summary-div project-name)]
    (b/wait-until-displayed q)
    (select-keys
     (->> (taxi/elements q)
          (mapv #(hash-map (keyword (taxi/attribute % :class))
                           (parse-integer (taxi/text %))))
          (apply merge))
     [:articles-reviewed :labels-contributed :annotations-contributed])))

(defn make-public-reviewer [user-id email]
  (with-transaction
    (users/create-email-verification! user-id email)
    (users/verify-email!
     email (:verify-code (users/read-email-verification-code user-id email)) user-id)
    (users/set-primary-email! user-id email)
    (if-let [user-group-id (:id (groups/read-user-group-name user-id "public-reviewer"))]
      (groups/set-user-group-enabled! user-group-id true)
      (groups/add-user-to-group! user-id (groups/group-name->group-id "public-reviewer")))))

(defn change-project-public-access
  "Change public access setting for current project."
  [public?]
  (let [status-q #(str "button#public-access_" (if % "public" "private"))
        q (status-q public?)]
    (nav/go-project-route "/settings")
    (b/wait-until-exists (-> q b/not-disabled))
    (log/infof "changing project access to %s" (if public? "public" "private"))
    (if (taxi/exists? (-> q b/not-disabled (b/not-class "active")))
      (do (b/click q)
          (b/click "div.project-options button.save-changes"))
      (do (log/warn "change-project-public-access: already set to" (str public? "?")
                    (pr-str [(taxi/attribute (status-q true) "class")
                             (taxi/attribute (status-q false) "class")]))
          (b/take-screenshot)))))

(deftest-browser correct-project-activity
  (test/db-connected?)
  [project-name-1 "Sysrev Browser Test (correct-project-activity 1)"
   project-name-2 "Sysrev Browser Test (correct-project-activity 2)"
   search-term "foo bar"
   email (:email b/test-login)
   user-id (:user-id (users/get-user-by-email email))
   click-project-link #(b/click (xpath "//a[contains(text(),'" % "')]"))]
  (do #_ (b/start-webdriver true)
      (nav/log-in)
      ;; subscribe to plans
      (plans/user-subscribe-to-unlimited email)
      ;; create a project, populate with articles
      (nav/new-project project-name-1)
      ;; set the project to private
      (change-project-public-access false)
      (pm/add-articles-from-search-term search-term)
      ;; go to the user profile
      (b/click user-name-link)
      (b/click "#user-projects")
      (Thread/sleep 250)
      ;; is the project-name listed in the private projects section?
      (b/is-soon (in? (private-project-names) project-name-1))
      ;; do some work to see if it shows up in the user profile
      (click-project-link project-name-1)
      (b/click (x/project-menu-item :review) :delay 50)
      ;; set three article labels
      (dotimes [n 3]
        (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
      ;; go back to profile, check activity
      (b/click user-name-link)
      (b/click "#user-projects")
      ;; is the user's overall activity correct?
      (Thread/sleep 500)
      (b/is-soon (= (select-keys (user-activity-summary)
                                 [:articles-reviewed :labels-contributed])
                    {:articles-reviewed 3 :labels-contributed 3}))
      ;; is the individual projects activity correct?
      (b/is-soon (= (select-keys (project-activity-summary project-name-1)
                                 [:articles-reviewed :labels-contributed])
                    {:articles-reviewed 3 :labels-contributed 3}))
      ;; let's do some annotations to see if they are showing up
      (click-project-link project-name-1)
      (nav/go-project-route "/articles" :wait-ms 200)
      (b/click "a.article-title" :delay 200)
      (annotator/annotate-article "foo" "bar" :offset-x 100)
      ;; return to the profile, the user should have one annotation
      (b/click user-name-link)
      (b/click "#user-projects")
      (Thread/sleep 500)
      ;; total activity
      (b/is-soon (= (user-activity-summary) {:articles-reviewed 3
                                             :labels-contributed 3
                                             :annotations-contributed 1}))
      ;; project activity
      (b/is-soon (= (project-activity-summary project-name-1) {:articles-reviewed 3
                                                               :labels-contributed 3
                                                               :annotations-contributed 1}))
      ;; add another project
      (nav/new-project project-name-2)
      (change-project-public-access false)
      (pm/add-articles-from-search-term search-term)
      ;; go to the profile
      (b/click user-name-link)
      (b/click "#user-projects")
      (Thread/sleep 300)
      ;; is the project-name listed in the private projects section?
      (b/is-soon (in? (private-project-names) project-name-2))
      ;; do some work to see if it shows up in the user profile
      (click-project-link project-name-2)
      ;; review two articles (save labels)
      (dotimes [n 2]
        (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
      ;; annotate
      (nav/go-project-route "/articles" :wait-ms 200)
      (b/click "a.article-title" :delay 200)
      (annotator/annotate-article "foo" "bar" :offset-x 100)
      ;; go back and check activity
      (b/click user-name-link)
      (b/click "#user-projects")
      (Thread/sleep 500)
      ;; total activity
      (b/is-soon (= (user-activity-summary) {:articles-reviewed 5
                                             :labels-contributed 5
                                             :annotations-contributed 2}))
      ;; project-1
      (b/is-soon (= (project-activity-summary project-name-1) {:articles-reviewed 3
                                                               :labels-contributed 3
                                                               :annotations-contributed 1}))
      ;; project-2
      (b/is-soon (= (project-activity-summary project-name-2) {:articles-reviewed 2
                                                               :labels-contributed 2
                                                               :annotations-contributed 1}))
      ;; make the first project, check that both projects show up in the correct divs
      (click-project-link project-name-1)
      (change-project-public-access true)
      (b/click user-name-link)
      (b/click "#user-projects")
      (Thread/sleep 500)
      (b/is-soon (and (in? (public-project-names) project-name-1)
                      (not (in? (private-project-names) project-name-1))))
      (b/is-soon (and (in? (private-project-names) project-name-2)
                      (not (in? (public-project-names) project-name-2)))))
  :cleanup (b/cleanup-test-user! :user-id user-id))

(deftest-browser user-description
  (test/db-connected?)
  [email-browser+test (:email b/test-login)
   password-browser+test (:password b/test-login)
   email-test-user "test@insilica.co"
   password-test-user "testinsilica"
   _ (b/create-test-user :email email-test-user :password password-test-user)
   user-id-test-user (:user-id (users/get-user-by-email email-test-user))
   user-id-browser+test (:user-id (users/get-user-by-email email-browser+test))
   user-introduction "I am the browser test"]
  (do
    ;; make test-user a public reviewer
    (make-public-reviewer user-id-test-user email-test-user)
    (nav/log-in email-test-user password-test-user)
    ;; go to the user profile
    (b/click user-name-link)
    (b/click user-profile-tab)
    ;; edit introduction
    (b/click edit-introduction)
    (b/wait-until-displayed "textarea")
    (b/set-input-text "textarea" user-introduction :delay 100)
    (markdown/click-save)
    (b/is-soon (b/exists? (xpath "//p[text()='" user-introduction "']")))
    ;; log in as test user
    (nav/log-in)
    ;; go to users
    (nav/go-route "/users")
    (b/click (xpath "//a[@href='/user/" user-id-test-user "/profile']"))
    ;; the introduction still reads the same
    (b/is-soon (b/exists? (xpath "//p[text()='" user-introduction "']")))
    ;; there is no edit introduction option
    (b/is-soon (not (taxi/exists? edit-introduction))))
  :cleanup (b/cleanup-test-user! :email email-test-user))

(deftest-browser user-avatar
  (test/db-connected?)
  [{:keys [user-id]} (users/get-user-by-email (:email b/test-login))]
  (do (nav/log-in)
      ;; go to the user profile
      (b/click "#user-name-link" :delay 250)
      (b/click "#user-profile" :delay 250)
      ;; click the user profile avatar
      (b/click avatar :delay 250)
      ;; "upload" file
      (log/info "uploading image")
      (taxi/execute-script upload-image-blob-js)
      (Thread/sleep 250)
      (log/info "waiting until displayed")
      ;; set position of avatar
      (b/wait-until-displayed (xpath "//button[contains(text(),'Set Avatar')]") 30000)
      (log/info "got image interface")
      (Thread/sleep 500)
      (->actions @b/active-webdriver
                 (move-to-element (taxi/element "div.cr-viewport") 0 0)
                 (click-and-hold) (move-by-offset 83 0) (release)
                 (perform))
      (Thread/sleep 500)
      ;; set avatar
      (log/info "setting avatar")
      (b/click (xpath "//button[contains(text(),'Set Avatar')]"))
      (Thread/sleep 1500)
      ;; check manually that the avatar matches what we would expect
      ;; (two possible values for some reason, depending on system)
      (is (contains? #{;; original test value (james mac)
                       "52d799d26a9a24d1a09b6bb88383cce385c7fb1b"
                       ;; second test value (jeff/james mac, jenkins linux)
                       "4ee9a0e6b3db1c818dd6f4a343260f639d457fb7"
                       ;; another value (jeff chromium linux)
                       "10ea7c8cc6223d6a1efd8de7b5e81ac3cf1bca92"}
                     (:key (files/avatar-image-key-filename user-id))))
      (log/info "got file key")
      (is (= (:meta (api/read-profile-image-meta user-id))
             {:points ["1" "120" "482" "600"], :zoom 0.2083, :orientation 1}))
      (log/info "got image meta")
      (is (-> (:key (files/avatar-image-key-filename user-id))
              (fstore/lookup-file :image)
              :object-content))
      (log/info "found file on s3"))
  :cleanup
  (do (Thread/sleep 1000)
      ;; sleep again before disconnecting db in case server handler (create-avatar!) is still running
      (api/delete-avatar! user-id)))

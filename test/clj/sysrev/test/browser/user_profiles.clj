(ns sysrev.test.browser.user_profiles
  (:require [clj-webdriver.core :refer [->actions move-to-element click-and-hold move-by-offset release perform]]
            [clj-webdriver.taxi :as taxi]
            [clojure.data.json :as json]
            [clojure.test :refer :all]
            [sysrev.api :as api]
            [sysrev.db.core :refer [with-transaction]]
            [sysrev.db.files :as files]
            [sysrev.db.project :as project]
            [sysrev.db.users :as users]
            [sysrev.test.browser.annotator :as annotator]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.markdown :as markdown]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.review-articles :as ra]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test]
            [sysrev.util :as util]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def user-name-link (xpath "//a[@id='user-name-link']"))
(def user-profile-tab (xpath "//a[@id='user-profile']"))
(def public-project-divs (xpath "//div[@id='public-projects']/div[contains(@id,'project-')]/a"))
(def private-project-divs (xpath "//div[@id='private-projects']/div[contains(@id,'project-')]/a"))
(def activity-values (xpath "//h2[contains(@class,'articles-reviewed' | 'labels-contributed' | 'annotations-contributed')]"))
(def user-activity-summary-div (xpath "//div[@id='user-activity-summary']" activity-values))
(defn project-activity-summary-div [project-name] (xpath "//a[contains(text(),'" project-name "')]"
                                                         "/ancestor::div[contains(@id,'project-')]"
                                                         activity-values))
(def edit-introduction (xpath "//a[@id='edit-introduction']"))

;; avatar
;; (def avatar (xpath "//img[contains(@src,'avatar')]"))
(def avatar (xpath "//div[@data-tooltip='Change Your Avatar']"))
(def upload-button (xpath "//button[contains(text(),'Upload Profile Image')]"))

;; from https://github.com/remvee/clj-base64/blob/master/src/remvee/base64.clj

(def alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(defn encode
  "Encode sequence of bytes to a sequence of base64 encoded
  characters."
  [bytes]
  (when (seq bytes)
    (let [t (->> bytes (take 3) (map #(bit-and (int %) 0xff)))
          v (int (reduce (fn [a b] (+ (bit-shift-left (int a) 8) (int b))) t))
          f #(nth alphabet (bit-and (if (pos? %)
                                      (bit-shift-right v %)
                                      (bit-shift-left v (* % -1)))
                                    0x3f))
          r (condp = (count t)
              1 (concat (map f [2 -4])    [\= \=])
              2 (concat (map f [10 4 -2]) [\=])
              3         (map f [18 12 6 0]))]
      (concat r (lazy-seq (encode (drop 3 bytes)))))))

(def image-base-64  (->> "test-files/demo-1.jpg"
                         clojure.java.io/resource
                         clojure.java.io/file
                         util/slurp-bytes
                         encode
                         (apply str)))

;; http://blog.fermium.io/how-to-send-files-to-a-dropzone-js-element-in-selenium/
(def upload-image-blob-js
  (str "var myZone, blob, base64Image; myZone = Dropzone.forElement('.dropzone');"
       "base64Image = '" image-base-64 "';"
       "function base64toBlob(r,e,n){e=e||\"\",n=n||512;for(var t=atob(r),a=[],o=0;o<t.length;o+=n){for(var l=t.slice(o,o+n),h=new Array(l.length),b=0;b<l.length;b++)h[b]=l.charCodeAt(b);var v=new Uint8Array(h);a.push(v)}var c=new Blob(a,{type:e});return c}"
       "blob = base64toBlob(base64Image, 'image / png');"
       "blob.name = 'testfile.png';"
       "myZone.addFile(blob);"))

(defn private-project-names
  []
  (b/wait-until-displayed private-project-divs)
  (->> private-project-divs
       taxi/find-elements
       (mapv taxi/text)))

(defn public-project-names
  []
  (b/wait-until-displayed public-project-divs)
  (->> public-project-divs
       taxi/find-elements
       (mapv taxi/text)))

(defn user-activity-summary
  []
  (b/wait-until-displayed user-activity-summary-div)
  (->> user-activity-summary-div
       taxi/find-elements
       (mapv #(hash-map (keyword (taxi/attribute % :class)) (read-string (taxi/text %))))
       (apply merge)))

(defn project-activity-summary
  [project-name]
  (b/wait-until-displayed (project-activity-summary-div project-name))
  (->> (project-activity-summary-div project-name)
       taxi/find-elements
       (mapv #(hash-map (keyword (taxi/attribute % :class)) (read-string (taxi/text %))))
       (apply merge)))

(defn make-public-reviewer
  [user-id email]
  (with-transaction
    (users/create-email-verification! user-id email)
    (users/verify-email! email (:verify-code (users/read-email-verification-code user-id email)) user-id)
    (users/set-primary-email! user-id email)
    (if-let [web-user-group-id (:id (users/read-web-user-group-name user-id "public-reviewer"))]
      (users/update-web-user-group! web-user-group-id true)
      (users/create-web-user-group! user-id "public-reviewer"))))

(deftest-browser correct-project-activity
  (test/db-connected?)
  [project-name-1 "Sysrev Browser Test (correct-project-activity 1)"
   project-name-2 "Sysrev Browser Test (correct-project-activity 2)"
   search-term "foo bar"
   email (:email b/test-login)
   user-id (-> (:email b/test-login)
               users/get-user-by-email
               :user-id)]
  (do (nav/log-in)
      ;; create a project, populate with articles
      (nav/new-project project-name-1)
      (pm/add-articles-from-search-term search-term)
      ;; go to the user profile
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; is the project-name listed in the private projects section?
      (is (= project-name-1 (first (private-project-names))))
      ;; do some work to see if it shows up in the user profile
      (b/click (xpath "//a[contains(text(),'" project-name-1 "')]"))
      (b/click (x/project-menu-item :review) :delay 50)
      ;; set three article labels
      (dotimes [n 3]
        (ra/set-article-answers [(merge ra/include-label-definition
                                        {:value true})]))
      ;; go back to profile, check activity
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; is the user's overall activity correct?
      (is (= 3 (:articles-reviewed (user-activity-summary))))
      (is (= 3 (:labels-contributed (user-activity-summary))))
      ;; is the individual projects activity correct?
      (is (= 3 (:articles-reviewed (project-activity-summary project-name-1))))
      (is (= 3 (:labels-contributed (project-activity-summary project-name-1))))
      ;; let's do some annotations to see if they are showing up
      (b/click (xpath "//a[contains(text(),'" project-name-1 "')]"))
      (nav/go-project-route "/articles")
      (b/wait-until-loading-completes :pre-wait 200)
      (b/click annotator/article-title-div :delay 200)
      (annotator/annotate-article "foo" "bar" :offset-x 100)
      ;; return to the profile, the user should have one annotation
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; total activity
      (is (= 3 (:articles-reviewed (user-activity-summary))))
      (is (= 3 (:labels-contributed (user-activity-summary))))
      (is (= 1 (:annotations-contributed (user-activity-summary))))
      ;; project activity
      (is (= 3 (:articles-reviewed (project-activity-summary project-name-1))))
      (is (= 3 (:labels-contributed (project-activity-summary project-name-1))))
      (is (= 1 (:annotations-contributed (project-activity-summary project-name-1))))
      ;; add another project
      (nav/new-project project-name-2)
      (pm/add-articles-from-search-term search-term)
      ;; go to the profile
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; is the project-name listed in the private projects section?
      (is (= project-name-2 (->> (private-project-names)
                                 (filter #(= % project-name-2))
                                 first)))
      ;; do some work to see if it shows up in the user profile
      (b/click (xpath "//a[contains(text(),'" project-name-2 "')]"))
      (b/click (x/project-menu-item :review) :delay 50)
      ;; set two article labels
      (dotimes [n 2]
        (ra/set-article-answers [(merge ra/include-label-definition
                                        {:value true})]))
      ;; annotate
      (nav/go-project-route "/articles")
      (b/wait-until-loading-completes :pre-wait 200)
      (b/click annotator/article-title-div :delay 200)
      (annotator/annotate-article "foo" "bar" :offset-x 100)
      ;; go back and check activity
      (b/click user-name-link)
      (b/click user-profile-tab)
      ;; total activity
      (is (= 5 (:articles-reviewed (user-activity-summary))))
      (is (= 5 (:labels-contributed (user-activity-summary))))
      (is (= 2 (:annotations-contributed (user-activity-summary))))
      ;; project-1
      (is (= 3 (:articles-reviewed (project-activity-summary project-name-1))))
      (is (= 3 (:labels-contributed (project-activity-summary project-name-1))))
      (is (= 1 (:annotations-contributed (project-activity-summary project-name-1))))
      ;; project-2
      (is (= 2 (:articles-reviewed (project-activity-summary project-name-2))))
      (is (= 2 (:labels-contributed (project-activity-summary project-name-2))))
      (is (= 1 (:annotations-contributed (project-activity-summary project-name-2))))
      ;; make the first project, check that both projects show up in the correct divs
      (b/click (xpath "//a[contains(text(),'" project-name-1 "')]"))
      (nav/go-project-route "/settings")
      (b/click (xpath "//button[@id='public-access_public']"))
      (b/click (xpath "//div[contains(@class,'project-options')]//button[contains(@class,'save-changes')]"))
      (b/click user-name-link)
      (b/click user-profile-tab)
      (taxi/wait-until #(= project-name-1 (first (public-project-names))))
      (is (= project-name-1 (first (public-project-names))))
      (taxi/wait-until #(= project-name-2 (first (private-project-names))))
      (is (= project-name-2 (first (private-project-names)))))
  :cleanup
  (do (->> (users/projects-member user-id) (mapv :project-id) (mapv project/delete-project))))

(deftest-browser user-description
  (test/db-connected?)
  [email-browser+test (:email b/test-login)
   password-browser+test (:password b/test-login)
   email-test-user "test@insilica.co"
   password-test-user "testinsilica"
   user-id-browser+test (-> (:email b/test-login)
                            users/get-user-by-email
                            :user-id)
   user-introduction "I am the browser test"]
  (do ;; create another test user
    (b/create-test-user :email email-test-user :password password-test-user)
    ;; make them a public reviewer
    (make-public-reviewer user-id-browser+test email-browser+test)
    (nav/log-in)
    ;; go to the user profile
    (b/click user-name-link)
    (b/click user-profile-tab)
    ;; edit introduction
    (b/click edit-introduction)
    (b/wait-until-displayed "textarea")
    (b/set-input-text "textarea" user-introduction :delay 100)
    (markdown/click-save)
    (b/exists? (xpath "//p[text()='" user-introduction "']"))
    ;; log in as another user
    (nav/log-in email-test-user password-test-user)
    ;; go to user
    (nav/go-route "/users")
    (b/click (xpath "//a[@href='/users/" user-id-browser+test "']"))
    ;; the introduction still reads the same
    (b/exists? (xpath "//p[text()='" user-introduction "']"))
    ;; there is no edit introduction option
    (is (not (taxi/exists? edit-introduction))))
  :cleanup
  (do (b/delete-test-user :email email-test-user)))

(deftest-browser user-avatar
  (test/db-connected?)
  [user-id (-> (:email b/test-login)
               users/get-user-by-email
               :user-id)]
  (do
    (nav/log-in)
    ;; go to the user profile
    (b/click user-name-link)
    (b/click user-profile-tab)
    ;; click the user profile avatar
    (b/click avatar)
    ;; "upload" file
    (taxi/execute-script upload-image-blob-js)
    ;; set position of avatar
    (b/wait-until-displayed (xpath "//button[contains(text(),'Set Avatar')]"))
    (->actions @b/active-webdriver
               (move-to-element (taxi/find-element @b/active-webdriver (xpath "//div[contains(@class,'cr-viewport')]")) 0 0)
               (click-and-hold) (move-by-offset 83 0) (release)
               (perform))
    ;; set avatar
    (b/click (xpath "//button[contains(text(),'Set Avatar')]"))
    ;; check manually that the avatar matches what we would expect
    (= (:key (files/avatar-image-key-filename user-id))
       "52d799d26a9a24d1a09b6bb88383cce385c7fb1b")
    (= (-> (api/read-profile-image-meta user-id) :result :meta)
       {:points ["1" "120" "482" "600"], :zoom 0.2083, :orientation 1}))
  :cleanup
  (do
    (api/delete-avatar! user-id)))

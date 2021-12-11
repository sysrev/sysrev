(ns sysrev.test.browser.user-profiles
  (:require [clojure.tools.logging :as log]
            [clj-webdriver.taxi :as taxi]
            [clojure.test :refer [is use-fixtures]]
            [sysrev.api :as api]
            [sysrev.db.core :refer [with-transaction]]
            [sysrev.file.s3 :as s3-file]
            [sysrev.file.user-image :as user-image]
            [sysrev.group.core :as group]
            ;; TODO: uncomment when addressing new labels UI tests
            [sysrev.user.core :as user :refer [
                                               ;user-by-email
                                               ]]
            ;[sysrev.test.browser.annotator :as annotator]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.markdown :as markdown]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.plans :as plans]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.test.browser.review-articles :as ra]
            [sysrev.test.browser.xpath :as x :refer [xpath]]
            [sysrev.test.core :as test]
            [sysrev.util :as util :refer [in? parse-integer ignore-exceptions]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(defn project-activity-summary-headers [project-name]
  (format ".user-project-entry[data-name='%s'] h2" project-name))

;; avatar
;; (def avatar (xpath "//img[contains(@src,'avatar')]"))
(def avatar (xpath "//div[@data-tooltip='Change Your Avatar']"))

(defn private-project-names []
  (->> (b/get-elements-text "#private-projects .user-project-entry .project-link")
       (mapv #(-> (re-find #"(.*)/(.*)" %) (nth 2)))))

(defn public-project-names []
  (->> (b/get-elements-text "#public-projects .user-project-entry .project-link")
       (mapv #(-> (re-find #"(.*)/(.*)" %) (nth 2)))))

(defn user-activity-summary []
  (b/wait-until-displayed "div.user-activity-summary")
  (let [articles-reviewed (parse-integer (taxi/text (taxi/element "h2.articles-reviewed")))
        labels-contributed (parse-integer (taxi/text (taxi/element "h2.labels-contributed")))
        annotations-contributed 0

        ;; LEGACY ANNOTATIONS
        #_(if (taxi/exists? "h2.annotations-contributed")
            (parse-integer (taxi/text (taxi/element "h2.annotations-contributed")))
            0)]
    {:articles-reviewed articles-reviewed
     :labels-contributed labels-contributed
     :annotations-contributed annotations-contributed}))

(defn project-activity-summary [project-name]
  (b/wait-until-displayed (project-activity-summary-headers project-name))
  (select-keys
   (->> (taxi/elements (project-activity-summary-headers project-name))
        (mapv #(hash-map (keyword (taxi/attribute % :class))
                         (parse-integer (taxi/text %))))
        (apply merge))
   [:articles-reviewed :labels-contributed :annotations-contributed]))

(defn make-public-reviewer [user-id email]
  (with-transaction
    (user/create-email-verification! user-id email)
    (user/verify-email!
     email (user/email-verify-code user-id email) user-id)
    (user/set-primary-email! user-id email)
    (if-let [user-group-id (:id (group/read-user-group-name user-id "public-reviewer"))]
      (group/set-user-group-enabled! user-group-id true)
      (group/add-user-to-group! user-id (group/group-name->id "public-reviewer")))))

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
          (b/take-screenshot :warn)))))

(defn change-project-label-blinding
  "Change label blinding setting for current project."
  [blinding?]
  (let [status-q #(str "button#blind-reviewers_" (if % "true" "false"))
        q (status-q blinding?)]
    (nav/go-project-route "/settings")
    (b/wait-until-exists (-> q b/not-disabled))
    (log/infof "changing label blinding to %s" (if blinding? "true" "false"))
    (if (taxi/exists? (-> q b/not-disabled (b/not-class "active")))
      (do (b/click q)
          (b/click "div.project-options button.save-changes"))
      (do (log/warn "change-label-blinding: already set to" (str blinding? "?")
                    (pr-str [(taxi/attribute (status-q true) "class")
                             (taxi/attribute (status-q false) "class")]))
          (b/take-screenshot :warn)))))

(deftest-browser correct-project-activity
  (and (test/db-connected?) (not (test/remote-test?))) test-user
  [{:keys [user-id email]} test-user
   project-name-1 "Sysrev Browser Test (correct-project-activity 1)"
   project-name-2 "Sysrev Browser Test (correct-project-activity 2)"
   click-project-link #(do (log/infof "loading project %s" (pr-str %))
                           (b/click (xpath "//a[contains(text(),'" % "')]")))]
  (do #_ (b/start-webdriver true)
      (nav/log-in email)
      ;; subscribe to plans
      (plans/user-subscribe-to-unlimited email)
      ;; create a project, populate with articles
      (nav/new-project project-name-1)
      ;; set the project to private
      (change-project-public-access false)
      (pm/import-pubmed-search-via-db "foo bar")
      ;; go to the user profile
      (b/click "#user-name-link")
      (b/click "#user-projects")
      ;; is the project-name listed in the private projects section?
      (b/is-soon (in? (private-project-names) project-name-1))
      ;; do some work to see if it shows up in the user profile
      (click-project-link project-name-1)
      (b/click (x/project-menu-item :review))
      ;; set three article labels
      (dotimes [_ 3]
        (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
      ;; go back to profile, check activity
      (b/click "#user-name-link")
      (b/click "#user-projects")
      ;; is the user's overall activity correct?
      (b/is-soon (= (select-keys (user-activity-summary)
                                 [:articles-reviewed :labels-contributed])
                    {:articles-reviewed 3 :labels-contributed 3}))
      ;; is the individual projects activity correct?
      (b/is-soon (= (select-keys (project-activity-summary project-name-1)
                                 [:articles-reviewed :labels-contributed])
                    {:articles-reviewed 3 :labels-contributed 3}))
      ;; let's do some annotations to see if they are showing up
      (click-project-link project-name-1)
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.article-title" :delay 100)
      ;; (annotator/annotate-article
      ;;  {:client-field "primary-title" :semantic-class "foo" :value "bar"
      ;;   :offset-x 99})
      ;; return to the profile, the user should have one annotation
      (b/click "#user-name-link")
      (b/click "#user-projects")
      ;; total activity
      (b/is-soon (= (user-activity-summary) {:articles-reviewed 3
                                             :labels-contributed 3
                                             :annotations-contributed 0}))
      ;; project activity
      (b/is-soon (= (project-activity-summary project-name-1) {:articles-reviewed 3
                                                               :labels-contributed 3}))
      ;; add another project
      (nav/new-project project-name-2)
      (change-project-public-access false)
      (pm/import-pubmed-search-via-db "foo bar")
      ;; go to the profile
      (b/click "#user-name-link")
      (b/click "#user-projects")
      ;; is the project-name listed in the private projects section?
      (b/is-soon (in? (private-project-names) project-name-2))
      ;; do some work to see if it shows up in the user profile
      (click-project-link project-name-2)
      ;; review two articles (save labels)
      (dotimes [_ 2]
        (ra/set-article-answers [(merge ra/include-label-definition {:value true})]))
      ;; annotate
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.article-title" :delay 100)
      ;; (annotator/annotate-article
      ;;  {:client-field "primary-title" :semantic-class "foo" :value "bar"
      ;;   :offset-x 99})
      ;; go back and check activity
      (b/click "#user-name-link")
      (b/click "#user-projects")
      ;; total activity
      (b/is-soon (= (user-activity-summary) {:articles-reviewed 5
                                             :labels-contributed 5
                                             ;; LEGACY ANNOTATIONS 2
                                             :annotations-contributed 0}))
      ;; project-1
      (b/is-soon (= (project-activity-summary project-name-1) {:articles-reviewed 3
                                                               :labels-contributed 3}))
      ;; project-2
      (b/is-soon (= (project-activity-summary project-name-2) {:articles-reviewed 2
                                                               :labels-contributed 2}))
      ;; make the first project, check that both projects show up in the correct divs
      (click-project-link project-name-1)
      (change-project-public-access true)
      (b/click "#user-name-link")
      (b/click "#user-projects")
      (b/is-soon (and (in? (public-project-names) project-name-1)
                      (not (in? (private-project-names) project-name-1))))
      (b/is-soon (and (in? (private-project-names) project-name-2)
                      (not (in? (public-project-names) project-name-2))))))

(deftest-browser user-description
  (test/db-connected?) test-user
  [user1 (b/create-test-user :email "test@insilica.co")
   user-introduction "I am the browser test"]
  (do
    ;; make test-user a public reviewer
    (make-public-reviewer (:user-id user1) (:email user1))
    (nav/log-in (:email user1))
    (b/wait-until-loading-completes :pre-wait 50)
    ;; go to the user profile
    (b/click "#user-name-link")
    (b/click "a#user-profile")
    ;; edit introduction
    (b/click "a#edit-introduction")
    (b/wait-until-displayed "textarea")
    (b/wait-until-loading-completes :pre-wait 50)
    (b/set-input-text-per-char "textarea" user-introduction)
    (markdown/click-save)
    (b/is-soon (taxi/exists? (xpath "//p[text()='" user-introduction "']")))
    ;; log in as test user
    (nav/log-in (:email test-user))
    (b/wait-until-loading-completes :pre-wait 50)
    ;; go to users
    (nav/go-route "/users" :wait-ms 100)
    (let [url (format "/user/%s/profile" (:user-id user1))]
      (if (test/remote-test?)
        (nav/go-route url)
        (b/click (xpath (format "//a[@href='%s']" url)))))
    ;; the introduction still reads the same
    (b/is-soon (taxi/exists? (xpath "//p[text()='" user-introduction "']")))
    ;; there is no edit introduction option
    (b/is-soon (not (taxi/exists? "a#edit-introduction"))))
  :cleanup (b/cleanup-test-user! :email (:email user1)))

(deftest-browser user-avatar
  (test/db-connected?) test-user
  [{:keys [user-id email]} test-user]
  (do (nav/log-in email)
      ;; go to the user profile
      (b/click "#user-name-link")
      (b/click "a#user-profile")
      ;; click the user profile avatar
      (b/click avatar :displayed? true :delay 100)
      ;; "upload" file
      (log/info "uploading image")
      (b/dropzone-upload "test-files/demo-1.jpg")
      (log/info "waiting until displayed")
      ;; set position of avatar
      (b/wait-until-displayed (xpath "//button[contains(text(),'Set Avatar')]"))
      (log/info "got image interface")
      (b/click-drag-element "div.cr-viewport" :offset-x 83 :delay 100)
      ;; set avatar
      (log/info "setting avatar")
      (b/click (xpath "//button[contains(text(),'Set Avatar')]"))
      ;; check manually that the avatar matches what we would expect
      ;; (multiple possible values for some reason, depending on system)
      (b/is-soon (contains? #{ ;; original test value (james mac)
                              "52d799d26a9a24d1a09b6bb88383cce385c7fb1b"
                              ;; second test value (jeff/james mac, jenkins linux)
                              "4ee9a0e6b3db1c818dd6f4a343260f639d457fb7"
                              ;; another value (jeff chromium linux)
                              "10ea7c8cc6223d6a1efd8de7b5e81ac3cf1bca92"
                              ;; another (jeff chromium linux wayland/sway)
                              "cec7c07b3259529052801ed1714f5d059326151c"
                              ;; staging
                              "441036789c551efdf5d04c8a7e120cd25b1c311a"
                              ;; after library updates
                              "24e6c82660c56ab1ed297eab7c5561f96e21acd3"
                              ;; chrome 90, linux
                              "4b8beb92ad915c6310dc44f3ac54d96840c577b3"}
                            (:key (user-image/user-active-avatar-image user-id)))
                 5000 250)
      (log/info "got file key")
      (is (= (:meta (api/read-profile-image-meta user-id))
             {:points ["1" "120" "482" "600"], :zoom 0.2083, :orientation 1}))
      (log/info "got image meta")
      (b/is-soon (-> (:key (user-image/user-active-avatar-image user-id))
                     (s3-file/lookup-file :image)
                     :object-content)
                 5000 500)
      (log/info "found file on s3"))
  :cleanup (when-not (-> (user-image/delete-user-avatar-image user-id) (ignore-exceptions))
             ;; try again in case server handler (create-avatar!) was still running
             (log/warn "delete-user-avatar-image failed on first attempt")
             (Thread/sleep 2000)
             (user-image/delete-user-avatar-image user-id)))

(def opt-in-toggle (xpath "//input[@id='opt-in-public-reviewer']"))
(def email-verified-label
  (xpath "//div[contains(@class,'label') and contains(@class,'email-verified')]"))
(def email-unverified-label
  (xpath "//div[contains(@class,'label') and contains(@class,'email-unverified')]"))
(def primary-label (xpath "//div[contains(text(),'Primary') and contains(@class,'label')]"))
(def add-new-email-address (xpath "//button[contains(text(),'Add a New Email Address')]"))
(def new-email-address-input (xpath "//input[@id='new-email-address']"))
(def submit-new-email-address (xpath "//button[@id='new-email-address-submit']"))
(def make-primary-button (xpath "//button[@id='make-primary-button']"))
(def delete-email-button (xpath "//button[@id='delete-email-button']"))

(defn email-address-row [email]
  (xpath "//h4[contains(text(),'" email "')]"
         "/ancestor::div[contains(@class,'row')]"))

(defn email-verified? [email]
  (b/exists? (xpath (email-address-row email) email-verified-label)))

(defn email-unverified? [email]
  (b/exists? (xpath (email-address-row email) email-unverified-label)))

(defn primary? [email]
  (b/exists? (xpath (email-address-row email) primary-label)))

(defn make-primary [email]
  (b/click (xpath (email-address-row email) make-primary-button)))

(defn delete-email-address [email]
  (b/click (xpath (email-address-row email) delete-email-button))
  (Thread/sleep 200))

(defn email-address-count []
  (count (taxi/elements "h4.email-entry")))

(defn your-projects-count []
  (->> (b/get-elements-text "div#your-projects h4")
       (remove #(contains? #{"Your Projects"
                             "You don't have any projects yet, create a new one to get started with sysrev!"}
                           %))
       count))

;; TODO: Update tests to new UI
#_(deftest-browser verify-email-and-project-invite
  (and (test/db-connected?)
       (not (test/remote-test?))) test-user
  [user1 {:email (str "foo" (util/random-id) "@insilica.co") :password "foobar"}
   new-email-address (str "bar" (util/random-id) "@insilica.co")]
  (do (b/create-test-user)
      (nav/register-user (:email user1) (:password user1))
      ;; the user can't be listed as a public reviewer
      (b/click "#user-name-link")
      (b/click "#user-settings")
      (b/wait-until-exists opt-in-toggle)
      (is (taxi/attribute opt-in-toggle "disabled"))
      ;; verify the email address
      (let [{:keys [user-id email]} (user-by-email (:email user1))
            verify-code (user/email-verify-code user-id email)]
        (b/init-route (str "/user/" user-id "/email/" verify-code))
        (is (email-verified? email))
        ;; add a new email address
        (b/click add-new-email-address)
        ;; check for a basic error
        (b/click submit-new-email-address)
        (is (b/check-for-error-message "New email address can not be blank!"))
        ;; add a new email address
        (b/set-input-text-per-char new-email-address-input new-email-address)
        (b/click submit-new-email-address)
        (is (email-unverified? new-email-address))
        ;; verify new email address
        (b/init-route (str "/user/" user-id "/email/"
                           (user/email-verify-code user-id new-email-address)))
        (is (email-verified? new-email-address))
        ;;make this email address primary
        (make-primary new-email-address)
        (is (primary? new-email-address))
        ;; make the original email primary again
        (make-primary (:email user1))
        (is (primary? (:email user1)))
        ;; delete the other email
        (delete-email-address new-email-address)
        ;; the email count should be 1
        (is (= 1 (email-address-count)))
        ;; opt-in as a public reviewer
        (b/click "#user-name-link")
        (b/click "#user-settings")
        (b/wait-until-exists opt-in-toggle)
        ;; due to the hacky nature of React Semantic UI toggle buttons,
        ;; you click the label, not the input
        (b/click (xpath "//label[@for='opt-in-public-reviewer']"))
        ;; go to the users page and see if we are listed
        (nav/go-route "/users")
        (is (b/exists? (xpath "//a[contains(text(),'foo')]")))
        ;; FIX: why is this b/init-route needed for the nav/log-in?
        (b/init-route "/")
        (nav/log-in (:email test-user))
        (nav/new-project "Invitation Test")
        ;; go to user and invite foo
        (nav/go-route "/users")
        (b/click (xpath (str "//a[contains(@href,'" user-id "')]")
                        "/ancestor::div"
                        "/descendant::div[@role='listbox']"))
        (b/click (xpath (str "//a[contains(@href,'" user-id "')]")
                        "/ancestor::div"
                        "/descendant::span[contains(text(),'Invitation Test')]"
                        "/ancestor::div[@role='option']"))
        (b/click (xpath "//button[contains(text(),'Invite')]"))
        (is (b/exists? (xpath "//div[contains(text(),'This user was invited as a paid-reviewer to Invitation Test')]")))
        ;; log in as foo and check invitation
        (nav/log-in (:email user1) (:password user1))
        ;; confirm we aren't a member of Invitation Test
        (b/wait-until-exists "#new-project.button")
        (is (= 0 (your-projects-count)))
        (b/click "#user-name-link")
        (b/click "#user-settings")
        (b/click (xpath "//a[@href='/user/" user-id "/invitations']"))
        ;; accept the invitation
        (b/click (xpath "//button[contains(text(),'Accept')]"))
        (is (b/exists? (xpath "//div[contains(text(),'You accepted this invitation')]")))
        ;; are we now a member of at least one project?
        (nav/go-route "/")
        (is (= 1 (your-projects-count)))))
  :cleanup (b/cleanup-test-user! :email (:email user1)))

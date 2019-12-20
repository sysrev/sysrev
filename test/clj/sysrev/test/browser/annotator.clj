(ns sysrev.test.browser.annotator
  (:require [clj-webdriver.taxi :as taxi]
            [clojure.string :as str]
            [clojure.test :refer [is use-fixtures]]
            [clojure.tools.logging :as log]
            [clojure-csv.core :as csv]
            [sysrev.api :as api]
            [sysrev.project.core :as project]
            [sysrev.export.core :as export]
            [sysrev.test.core :as test]
            [sysrev.test.browser.core :as b :refer [deftest-browser]]
            [sysrev.test.browser.xpath :as x]
            [sysrev.test.browser.navigate :as nav]
            [sysrev.test.browser.review-articles :as review-articles]
            [sysrev.test.browser.pubmed :as pm]
            [sysrev.shared.util :as sutil :refer [in? ensure-pred css]]))

(use-fixtures :once test/default-fixture b/webdriver-fixture-once)
(use-fixtures :each b/webdriver-fixture-each)

(def save-annotation-button ".ui.form.edit-annotation .ui.button.positive[type=submit]")
(def edit-annotation-icon ".ui.form.edit-annotation i.pencil.icon")
(def sidebar-el
  (-> "div.panel-side-column > div.visibility-wrapper"
      (b/not-class "placeholder")
      (b/not-class "constraint")))
(def annotation-value-input
  (b/not-disabled (css sidebar-el "div.field.value" "input")))
(def semantic-class-input
  (css sidebar-el "div.field.semantic-class" "input[type=text]"))
(def semantic-class-dropdown
  (css sidebar-el "div.field.semantic-class" ".ui.selection.dropdown"))
(def semantic-class-dropdown-input
  (css semantic-class-dropdown "input.search"))
(defn semantic-class-dropdown-item [semantic-class]
  (css semantic-class-dropdown
       (format "div.menu > div.item[data-value='%s']" semantic-class)))
(def semantic-class-new-button
  (-> (css sidebar-el "div.field.semantic-class" ".ui.button.new-semantic-class")
      (b/not-disabled)))
(def article-labels-view ".ui.segments.article-labels-view")

(defn to-user-name [s]
  (first (str/split s #"@")))

(defn user-profile-link [username]
  (format "%s .ui.segment a.user-public-profile[data-username='%s']"
          article-labels-view (to-user-name username)))

(defn remove-quotes [s]
  (->> s seq (drop 1) reverse (drop 1) reverse (apply str)))

(defn article-view-annotations
  "Returns sequence of visible annotations shown for the current article in the
  read-only interface (div.article-labels-view, not sidebar)."
  []
  (Thread/sleep 20)
  (mapv #(let [[s1 s2 s3] (->> (taxi/find-elements-under % {:css ".ui.label"})
                               (mapv taxi/text))]
           {:selection (remove-quotes s1)
            :semantic-class (str/trim s2)
            :value (str/trim s3)})
        (taxi/elements ".ui.form.segment.user-annotation")))

(defn sidebar-annotations
  "Returns sequence of visible annotations shown for the current article in the
  sidebar interface."
  []
  (Thread/sleep 20)
  (mapv (fn [form-el]
          (letfn [(find-el [q]
                    (some->> (taxi/find-elements-under form-el {:css q})
                             (ensure-pred #(<= (count %) 1))
                             first))
                  (find-input-value [parent-q]
                    (or (try (some-> (find-el (css parent-q "input[type=text]"))
                                     (taxi/value))
                             (catch Throwable _ nil))
                        (try (some-> (find-el (css parent-q ".item.active.selected"))
                                     (taxi/attribute "data-value"))
                             (catch Throwable _ nil))
                        ""))]
            {:selection (remove-quotes (taxi/text (find-el ".ui.label.selection-label")))
             :semantic-class (find-input-value ".field.semantic-class")
             :value (find-input-value ".field.value")}))
        (taxi/elements (css sidebar-el ".ui.form.edit-annotation"))))

(defn annotation-highlights []
  (->> (taxi/find-elements {:css "div.annotated-text-toplevel span.annotated-text"})
       (filter taxi/displayed?)
       (mapv taxi/text)))

(defn check-highlights [annotations]
  (Thread/sleep 20)
  (is (= (set (map :selection annotations))
         (set (annotation-highlights)))))

(defn check-annotations-csv [project-id]
  (let [annotations (api/project-annotations project-id)
        annotations-csv (rest (export/export-annotations-csv project-id))]
    (is (= (count annotations) (count annotations-csv)))
    (is (= annotations-csv (-> (csv/write-csv annotations-csv)
                               (csv/parse-csv :strict true))))
    (doseq [ann annotations]
      (is (some (fn [row]
                  (and (in? row (str (:value ann)))
                       (in? row (str (:semantic-class ann)))
                       (in? row (-> ann :context :client-field))
                       (in? row (str (-> ann :context :start-offset)))
                       (in? row (str (-> ann :context :end-offset)))))
                annotations-csv)))))

(defn input-semantic-class [semantic-class]
  (let [input-q (b/not-disabled semantic-class-input)
        dd-menu-q (b/not-disabled semantic-class-dropdown-input)
        dd-item-q (b/not-disabled (semantic-class-dropdown-item semantic-class))]
    (cond
      ;; check if have text input field (no default selected)
      (taxi/element input-q)
      (b/set-input-text input-q semantic-class) ; enter value into text input
      ;; otherwise have dropdown menu
      (taxi/element dd-menu-q)
      (do (b/click dd-menu-q :delay 50) ; click dropdown input to expand menu
          (if (taxi/element dd-item-q) ; check if the value we want is already in the list
            (b/click dd-item-q :delay 50) ; value found, click to select
            ;; menu doesn't have this value
            (do (b/click dd-menu-q :delay 50) ; collapse dropdown menu
                (b/click semantic-class-new-button) ; click button to add a new value
                (b/set-input-text input-q semantic-class) ; enter the new value into text input
                )))
      :else (log/warn "no dropdown input found"))))

(defn check-db-annotation
  [project-id match-fields {:keys [selection value semantic-class client-field user-id]
                            :as check-values}]
  (let [value-keys [:selection :value :semantic-class :client-field :user-id]
        entry (some->> (api/project-annotations project-id)
                       (map #(-> (assoc % :client-field (:client-field (:context %)))
                                 (assoc :value (:annotation %))
                                 (dissoc :annotation)))
                       (filter #(= (select-keys % match-fields)
                                   (select-keys check-values match-fields)))
                       (ensure-pred #(= 1 (count %)))
                       first)]
    (is entry "no matching annotation found")
    (when entry
      (is (= (select-keys check-values value-keys)
             (select-keys entry value-keys)))
      (is (= (:selection entry) (subs (-> entry :context :text-context)
                                      (-> entry :context :start-offset)
                                      (-> entry :context :end-offset)))))))

(defn annotate-article [{:keys [client-field semantic-class value selection
                                start-x offset-x start-y offset-y] :as entry}
                        & {:keys [retry] :or {retry 0}}]
  (let [q (format "div.annotated-text-toplevel[data-field='%s'] > div > div" client-field)
        entry-vals {:selection selection
                    :semantic-class semantic-class
                    :value value}
        check-values (fn []
                       (Thread/sleep 20)
                       (let [fields (cond-> [:semantic-class :value]
                                      selection (conj :selection))]
                         (is (in? (->> (sidebar-annotations)
                                       (map #(select-keys % fields) ))
                                  (select-keys entry-vals fields)))))
        retry-with #(annotate-article % :retry (inc retry))
        throw-mismatch #(throw (ex-info "annotate-article selection mismatch"
                                        {:expected selection :actual %}))]
    (if (zero? retry)
      (do (log/info "creating annotation")
          (b/wait-until-loading-completes :pre-wait 50)
          (b/click ".ui.button.change-labels" :if-not-exists :skip)
          (b/click x/review-annotator-tab)
          (b/wait-until-displayed ".annotation-menu .menu-header"))
      (do (log/info "retrying annotation:" (pr-str entry))
          (b/click ".ui.button.cancel-edit" :if-not-exists :skip)))
    (b/click-drag-element q
                          :start-x start-x :offset-x offset-x
                          :start-y (+ 2 (or start-y 0)) :offset-y offset-y)
    (let [new-ann (first (sidebar-annotations))
          new-sel (:selection new-ann)
          len-diff (Math/abs (- (count new-sel) (count selection)))]
      (cond (or (nil? selection) (= new-sel selection))
            (do (input-semantic-class semantic-class)
                (b/set-input-text annotation-value-input value)
                (check-values)
                (b/click save-annotation-button)
                (b/wait-until-exists edit-annotation-icon)
                (check-values))
            (and (<= retry 3) (<= 1 len-diff 2))
            (cond (and (< (count new-sel) (count selection))
                       (str/starts-with? selection new-sel))
                  (retry-with (update entry :offset-x #(+ (or % 0) 2)))
                  (and (< (count new-sel) (count selection))
                       (str/ends-with? selection new-sel))
                  (retry-with (-> (update entry :offset-x #(+ (or % 0) 2))
                                  (update :start-x #(- (or % 0) 2))))
                  (and (> (count new-sel) (count selection))
                       (str/starts-with? new-sel selection))
                  (retry-with (update entry :offset-x #(- (or % 0) 2)))
                  (and (> (count new-sel) (count selection))
                       (str/ends-with? new-sel selection))
                  (retry-with (-> (update entry :offset-x #(- (or % 0) 2))
                                  (update :start-x #(+ (or % 0) 2))))
                  :else (throw-mismatch new-sel))
            :else (throw-mismatch new-sel)))))

(defn edit-annotation [{:keys [semantic-class value]} new-values]
  (log/info "editing annotation")
  (let [q (css sidebar-el
               (cond-> "div.ui.segment.annotation-view"
                 semantic-class (str (format "[data-semantic-class='%s']" semantic-class))
                 value (str (format "[data-annotation='%s']" value))))]
    (b/click (css q ".ui.button.edit-annotation"))
    (when (:semantic-class new-values)
      (input-semantic-class (:semantic-class new-values)))
    (when (:value new-values)
      (b/set-input-text annotation-value-input (:value new-values)))
    (let [save-button (b/not-disabled (css q ".ui.button.save-annotation"))]
      (if (taxi/exists? save-button)
        (b/click save-button)
        (b/click (css q ".ui.button.cancel-edit"))))))

(defn delete-annotation [{:keys [semantic-class value]}]
  (log/info "deleting annotation")
  (b/wait-until-loading-completes :pre-wait 25 :inactive-ms 25)
  (let [q (css sidebar-el
               (cond-> "div.ui.segment.annotation-view"
                 semantic-class (str (format "[data-semantic-class='%s']" semantic-class))
                 value (str (format "[data-annotation='%s']" value))))
        elts (taxi/find-elements {:css q})]
    (cond (empty? elts)        (log/warn "no annotation entry found" q)
          (> (count elts) 1)   (log/warn "matched multiple annotation entries" q)
          :else                (b/click (css q ".ui.button.delete-annotation") :delay 50))))

(deftest-browser annotation-text
  ;; disabled; covered by annotator-interface test
  (and false (test/db-connected?)) test-user
  [project-name "Browser Test (annotation-text)"
   ann1 {:client-field "primary-title"
         :selection "Important roles of enthalpic and entropic contributions to CO2 capture from simulated flue gas"
         :semantic-class "foo"
         :value "bar"
         :offset-x 670}
   project-id (atom nil)]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (pm/import-pubmed-search-via-db "foo bar enthalpic mesoporous")
;;;; start annotating articles
      ;; review the single article result
      (b/click (x/project-menu-item :review))
      (b/wait-until-exists "div#project_review")
      (review-articles/set-article-answers
       [(merge review-articles/include-label-definition {:value true})])
      (b/wait-until-exists ".no-review-articles")
      ;; select one article and annotate it
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.article-title")
      (annotate-article ann1)
      ;;check the annotation
      (let [{:keys [user-id]} test-user
            project-id (review-articles/get-user-project-id user-id)
            article-id (first (project/project-article-ids project-id))
            {:keys [annotations]} (api/article-user-annotations article-id)
            annotation (first annotations)
            annotations-csv (rest (export/export-annotations-csv project-id))
            [csv-row] annotations-csv]
        (is (= (count annotations) 1))
        (is (= (:semantic-class ann1) (:semantic-class annotation)))
        (is (= (:value ann1) (:annotation annotation)))
        (is (= (get-in annotation [:context :text-context :field]) "primary-title"))
        (is (= (get-in annotation [:context :client-field]) "primary-title"))
        (is (= 0 (get-in annotation [:context :start-offset])))
        (is (= 94 (get-in annotation [:context :end-offset])))
        ;; do we have highlights?
        (is (= (:selection ann1) (taxi/text (taxi/element "span.annotated-text"))))
        ;; check annotations csv export
        (is (= 1 (count annotations-csv)))
        (is (in? csv-row (:value ann1)))
        (is (in? csv-row (:semantic-class ann1)))
        (is (in? csv-row "primary-title"))
        (is (in? csv-row "0"))
        (is (in? csv-row "94"))
        (is (= annotations-csv (-> (csv/write-csv annotations-csv)
                                   (csv/parse-csv :strict true)))))))

(deftest-browser annotator-interface
  (test/db-connected?) test-user
  [project-name "Browser Test (annotator-interface)"
   project-id (atom nil)
   test-users (mapv #(b/create-test-user :email %)
                    (mapv #(str "user" % "@fake.com") [1 2]))
   [user1 user2] test-users
   to-user-name #(-> % (str/split #"@") first)
   switch-user (fn [email]
                 (nav/log-in email)
                 (nav/go-project-route "" :project-id @project-id :silent true :wait-ms 50))
   ann-defs
   [{:client-field "primary-title"
     :selection
     "Important roles of enthalpic and entropic contributions to CO2 capture from simulated flue gas"
     :semantic-class "class1"
     :value "value1"
     :offset-x 670}
    {:client-field "abstract"
     :selection "The measurement"
     :semantic-class "class2"
     :value ""
     :offset-x 111}
    {:client-field "abstract"
     :selection "amine sites"
     :semantic-class "class1"
     :value "value2"
     :start-y 20 :start-x 26 :offset-x 68}]
   [ann1-def ann2-def ann3-def] ann-defs
   ann-vals (mapv #(select-keys % [:selection :semantic-class :value]) ann-defs)
   [ann1 ann2 ann3] ann-vals]
  (do (nav/log-in (:email test-user))
      (nav/new-project project-name)
      (reset! project-id (b/current-project-id))
      (pm/import-pubmed-search-via-db "foo bar enthalpic mesoporous")
      (doseq [{:keys [user-id]} test-users]
        (project/add-project-member @project-id user-id))
      (switch-user (:email user1))
      (nav/go-project-route "/review")
      ;; select a value for "Include", don't save yet
      (review-articles/set-label-answer {:short-label "Include" :value-type "boolean" :value true})
      ;; switch to annotator interface
      (b/click "a.item.tab-annotations")
      ;; save an annotation
      (annotate-article ann1-def)
      (b/click ".ui.button.save-labels")
      (b/wait-until-exists ".no-review-articles")
      (is (not (taxi/exists? sidebar-el)))
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.article-title" :delay 100)
      (is (not (taxi/exists? sidebar-el)))
      ;; check that annotation is visible in read-only article view
      (b/exists? (user-profile-link (:email user1)))
      (b/is-soon (= (article-view-annotations) [ann1]) 2000 30)
      (b/click ".ui.button.change-labels")
      (b/exists? sidebar-el)
      (b/exists? (user-profile-link (:email user1)))
      (b/exists? "div.label-editor-view")
      ;; check that still visible after clicking "Change Labels"
      (b/is-soon (= (article-view-annotations) [ann1]) 2000 30)
      (b/click "a.item.tab-annotations")
      ;; check that still visible with annotator tab selected
      (b/is-soon (= (article-view-annotations) [ann1]) 2000 30)
      ;; check for highlights in article text
      (check-highlights [ann1])
      ;; check that saved annotation appears in sidebar interface
      (is (= (sidebar-annotations) [ann1]))
      (annotate-article ann2-def)
      ;; check that both annotations appear in article view
      (b/is-soon (= (set (article-view-annotations)) (set [ann1 ann2])) 2000 30)
      (check-highlights [ann1 ann2])
      ;; check that both annotations appear in sidebar view
      (is (= (set (sidebar-annotations)) (set [ann1 ann2])))
      ;; switch to other user
      (switch-user (:email user2))
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.article-title")
      (b/exists? (user-profile-link (:email user1)))
      ;; check that both annotations appear in article view (user2)
      (b/is-soon (= (set (article-view-annotations)) (set [ann1 ann2])) 2000 30)
      (is (not (taxi/exists? sidebar-el)))
      ;; review same article with user2
      (nav/go-project-route "/review")
      (review-articles/set-label-answer {:short-label "Include" :value-type "boolean" :value true})
      ;; switch to annotator interface
      (b/click "a.item.tab-annotations")
      ;; save an annotation with user2
      (annotate-article ann3-def)
      ;; save article labels
      (b/click ".ui.button.save-labels")
      (b/wait-until-exists ".no-review-articles")
      (is (not (taxi/exists? sidebar-el)))
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.article-title")
      (b/exists? (user-profile-link (:email user1)))
      (b/exists? (user-profile-link (:email user2)))
      ;; check that all annotations appear in article view
      (b/is-soon (= (set (article-view-annotations)) (set [ann1 ann2 ann3])) 2000 30)
      (is (not (taxi/exists? sidebar-el)))
      ;; open label/annotation editor interface
      (b/click ".ui.button.change-labels")
      (b/exists? sidebar-el)
      ;; switch to annotator interface
      (b/click "a.item.tab-annotations")
      ;; check that only the annotation from user2 is shown in sidebar
      (b/is-soon (= (sidebar-annotations) [ann3]) 2000 30)
      ;; check that all annotations still visible in article view
      (b/is-soon (= (set (article-view-annotations)) (set [ann1 ann2 ann3])) 2000 30)
      (check-highlights [ann1 ann2 ann3])
      ;; check valid db values stored for each annotation
      (check-db-annotation
       @project-id [:selection]
       (merge ann1-def {:user-id (:user-id user1)}))
      (check-db-annotation
       @project-id [:selection]
       (merge ann2-def {:user-id (:user-id user1)}))
      (check-db-annotation
       @project-id [:selection]
       (merge ann3-def {:user-id (:user-id user2)}))
      (check-annotations-csv @project-id)
      ;; switch back to first user
      (switch-user (:email user1))
      ;; navigate back to the article
      (nav/go-project-route "/articles" :wait-ms 100)
      (b/click "a.article-title")
      ;; check that all annotations are shown from user1
      (b/is-soon (= (set (article-view-annotations)) (set [ann1 ann2 ann3])) 2000 30)
      ;; open annotator interface
      (b/click ".ui.button.change-labels")
      (b/click "a.item.tab-annotations")
      ;; check that only annotations from user1 shown in sidebar interface
      (b/is-soon (= (set (sidebar-annotations)) (set [ann1 ann2])) 2000 30)
      (check-highlights [ann1 ann2 ann3])
      ;; try changing an existing annotation
      (edit-annotation ann1 {:value "value1-changed"})
      ;; bind updated map for ann1
      (let [ann1 (assoc ann1 :value "value1-changed")]
        ;; check that article view was updated after edit
        (b/is-soon (= (set (article-view-annotations)) (set [ann1 ann2 ann3])) 2000 30)
        ;; check for correct sidebar view entries
        (is (= (set (sidebar-annotations)) (set [ann1 ann2])))
        ;; check for correct db values on edited annotation
        (check-db-annotation
         @project-id [:selection]
         (merge ann1-def ann1 {:user-id (:user-id user1)}))
        ;; now try editing semantic-class value
        (edit-annotation ann1 {:semantic-class "class1-changed"})
        (let [ann1 (assoc ann1 :semantic-class "class1-changed")]
          ;; check correct values for everything again
          (b/is-soon (= (set (article-view-annotations)) (set [ann1 ann2 ann3])) 2000 30)
          (is (= (set (sidebar-annotations)) (set [ann1 ann2])))
          (check-db-annotation
           @project-id [:selection]
           (merge ann1-def ann1 {:user-id (:user-id user1)}))
          (check-annotations-csv @project-id)
          (is (= 3 (count (api/project-annotations @project-id))))
          ;; delete an annotation
          (delete-annotation ann1)
          ;; check that deleted annotation is removed everywhere
          (b/is-soon (= (set (article-view-annotations)) (set [ann2 ann3])) 2000 30)
          (check-highlights [ann2 ann3])
          (is (= (sidebar-annotations) [ann2]))
          (is (= 2 (count (api/project-annotations @project-id))))
          (check-annotations-csv @project-id)
          ;; delete other annotation
          (delete-annotation ann2)
          (b/is-soon (= (article-view-annotations) [ann3]) 2000 30)
          (check-highlights [ann3])
          (is (empty? (sidebar-annotations)))
          (is (= 1 (count (api/project-annotations @project-id)))))))
  :cleanup (doseq [{:keys [email]} test-users]
             (b/cleanup-test-user! :email email)))

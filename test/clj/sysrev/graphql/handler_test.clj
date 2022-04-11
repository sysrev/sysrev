(ns sysrev.graphql.handler-test
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [clojure.walk :as walk]
   [honeysql.helpers :as sqlh :refer [from select sset where]]
   [medley.core :as medley]
   [sysrev.api :as api]
   [sysrev.datasource.api :refer [ds-auth-key]]
   [sysrev.db.core :as db]
   [sysrev.label.answer :as answer]
   [sysrev.project.core :as project]
   [sysrev.project.member :as member]
   [sysrev.test.core :as test :refer [graphql-request]]
   [sysrev.user.interface :as user]
   [venia.core :as venia]))

(defn project-article-ids
  [project-id]
  (-> (select :article_id)
      (from :article)
      (where [:= :project-id project-id])
      db/do-query))

(defn article-labels
  [project-id]
  (-> (select :label-id-local :label-id :value-type :root-label-id-local :definition)
      (from :label)
      (where [:= :project-id project-id])
      db/do-query))

(defn create-answer-map
  "Create an answer map for the labels in project-id, all answers are the same"
  [project-id]
  (let [labels (->> (article-labels project-id)
                    (map #(assoc % :answer
                                 (condp = (:value-type %)
                                   "boolean" true
                                   "string" ["Answer"]
                                   "categorical" (get-in % [:definition :inclusion-values])
                                   "group" nil))))
        labels->answer-map (fn [labels]
                             (->> labels
                                  (map #(hash-map (:label-id %)
                                                  (:answer %)))
                                  (apply merge)))
        non-group-labels (->> labels
                              (filter #(and (not= (:value-type %) "group")
                                            (nil? (:root-label-id-local %))))
                              labels->answer-map)
        group-labels (->> labels
                          (filter #(= (:value-type %) "group"))
                          (map #(hash-map (:label-id %)
                                          {:labels {"0" (->> labels
                                                             (filter (fn [label]
                                                                       (= (:root-label-id-local label)
                                                                          (:label-id-local %))))
                                                             labels->answer-map)}}))
                          (apply merge))]
    (merge non-group-labels group-labels)))

(defn review-all-articles
  [project-id user-id]
  (doall (map (fn [{:keys [article-id]}]
                (answer/set-user-article-labels user-id
                                                article-id
                                                (create-answer-map project-id)
                                                :imported? false
                                                :confirm? false
                                                :change? false
                                                :resolve? false))
              (project-article-ids project-id))))

(defn short-label->name
  "Convert all short-label keys to name"
  [m]
  (walk/postwalk (fn [x] (if (map? x)
                           (set/rename-keys x {:value-type :type :short-label :name})
                           x))
                 m))

(deftest ^:integration test-paywall
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (let [{:keys [api-token user-id]} (test/create-test-user system)
          test-user-2 (test/create-test-user system)
          project-name "Graphql - Paywall Test"
          {{:keys [project-id]} :project} (api/create-project-for-user!
                                           sr-context
                                           "Graphql - Paywall Test"
                                           user-id
                                           true)]
      (user/change-user-setting user-id :dev-account-enabled? true)
      (user/change-user-setting (:user-id test-user-2) :dev-account-enabled? true)
      (member/add-project-member project-id (:user-id test-user-2) :permissions ["admin" "member"])
      (is (= {:name project-name}
             (->> (graphql-request
                   system
                   (venia/graphql-query
                    {:venia/queries
                     [[:project {:id project-id} [:name]]]})
                   :api-key api-token)
                  :data :project))
          "Basic plan user can access public projects")
      (project/change-project-setting project-id :public-access false)
      (is (= ["This request requires an upgraded plan"]
             (->> (graphql-request
                   system
                   (venia/graphql-query
                    {:venia/queries
                     [[:project {:id project-id} [:name]]]})
                   :api-key api-token)
                  :errors
                  (map :message)))
          "Basic plan user can't access private projects")
      (test/change-user-plan! system user-id "Unlimited_Org_Annual_free")
      (is (= {:name project-name}
             (->> (graphql-request
                   system
                   (venia/graphql-query
                    {:venia/queries
                     [[:project {:id project-id} [:name]]]})
                   :api-key api-token)
                  :data :project))
          "Pro plan user can access private projects")
      (is (= {:name project-name}
             (->> (graphql-request
                   system
                   (venia/graphql-query
                    {:venia/queries
                     [[:project {:id project-id} [:name]]]})
                   :api-key (:api-token test-user-2))
                  :data :project))
          "Basic plan user can access private projects owned by a pro plan user"))))

(deftest ^:integration test-project-query
  (test/with-test-system [{:keys [sr-context] :as system} {}]
    (let [{:keys [user-id]} (test/create-test-user system)
          project-name "Graphql - Project Query Test"
          {{:keys [project-id]} :project} (api/create-project-for-user!
                                           sr-context project-name user-id false)
          test-user-1 (test/create-test-user system :email "user1@foo.bar")
          test-user-2 (test/create-test-user system :email "user2@foo.bar")
          include-label (-> (select :*)
                            (from :label)
                            (where [:and
                                    [:= :project-id project-id]
                                    [:= :short-label "Include"]])
                            db/do-query
                            first
                            (#(hash-map (:label-id %) %)))
          label-definitions (merge include-label
                                   {"new-label-DEDVqR"
                                    {:category "inclusion criteria",
                                     :definition {:inclusion-values [true]},
                                     :name "booleanytzeVO",
                                     :consensus true,
                                     :question "What is a foo?",
                                     :project-ordering 1,
                                     :short-label "Foo",
                                     :label-id "new-label-DEDVqR",
                                     :project-id 136,
                                     :enabled true,
                                     :value-type "boolean",
                                     :required true},
                                    "new-label-SjDeaW"
                                    {:category "extra",
                                     :labels
                                     {"new-label-nvmqHH"
                                      {:category "inclusion criteria",
                                       :definition
                                       {:inclusion-values ["five"],
                                        :multi? true,
                                        :all-values ["four" "five" "six"]},
                                       :name "categoricalBtMMon",
                                       :consensus true,
                                       :question "What is a charlie?",
                                       :project-ordering 2,
                                       :short-label "Charlie",
                                       :label-id "new-label-nvmqHH",
                                       :project-id 136,
                                       :enabled true,
                                       :value-type "categorical",
                                       :required true},
                                      "new-label-wFMFmz"
                                      {:category "extra",
                                       :definition
                                       {:multi? true,
                                        :max-length 100,
                                        :regex [".*"],
                                        :examples ["X-ray" "Yankee" "Zulu"]},
                                       :name "stringeNZKBG",
                                       :consensus true,
                                       :question "What is a delta?",
                                       :project-ordering 3,
                                       :short-label "Delta",
                                       :label-id "new-label-wFMFmz",
                                       :project-id 136,
                                       :enabled true,
                                       :value-type "string",
                                       :required true},
                                      "new-label-mlvYeO"
                                      {:category "extra",
                                       :definition {:inclusion-values []},
                                       :name "booleanvIucPI",
                                       :consensus true,
                                       :question "What is a bravo?",
                                       :project-ordering 1,
                                       :short-label "Bravo",
                                       :label-id "new-label-mlvYeO",
                                       :project-id 136,
                                       :enabled true,
                                       :value-type "boolean",
                                       :required true}},
                                     :definition {:multi? true},
                                     :name "groupJnjeAu",
                                     :project-ordering 4,
                                     :short-label "Alpha",
                                     :label-id "new-label-SjDeaW",
                                     :project-id 136,
                                     :enabled true,
                                     :value-type "group",
                                     :required false},
                                    "new-label-VfxdhO"
                                    {:category "extra",
                                     :definition
                                     {:multi? true,
                                      :max-length 100,
                                      :regex [".*"],
                                      :examples ["corge" "grault"]},
                                     :name "stringEpsUxh",
                                     :consensus true,
                                     :question "What is a baz?",
                                     :project-ordering 3,
                                     :short-label "Baz",
                                     :label-id "new-label-VfxdhO",
                                     :project-id 136,
                                     :enabled true,
                                     :value-type "string",
                                     :required true},
                                    "new-label-aaPdYS"
                                    {:category "inclusion criteria",
                                     :definition
                                     {:inclusion-values ["two"],
                                      :multi? true,
                                      :all-values ["one" "two" "three"]},
                                     :name "categoricalJkkoNl",
                                     :consensus true,
                                     :question "What is bar?",
                                     :project-ordering 2,
                                     :short-label "Bar",
                                     :label-id "new-label-aaPdYS",
                                     :project-id 136,
                                     :enabled true,
                                     :value-type "categorical",
                                     :required true}})]
      ;; set the user's api key to use that of sysrev's datasource api key
      (doall (-> (sqlh/update :web_user)
                 (sset {:api-token (ds-auth-key)})
                 (where [:= :user-id user-id])
                 db/do-execute))
      ;; populate with articles from datasource
      (is (-> (venia/graphql-query
               {:venia/operation {:operation/type :mutation :operation/name "M"}
                :venia/queries
                [[:importArticles {:id project-id
                                   :query (str/escape (venia/graphql-query
                                                       {:venia/queries
                                                        [[:entitiesByExternalIds
                                                          {:dataset 5
                                                           :externalIds ["513" "682"]} [:id]]]}) {\" "\\\""})}]]})
              (->> (graphql-request system))
              (get-in [:data :importArticles])))
      ;; create label definitions
      (is (:valid? (api/sync-labels project-id label-definitions)))
      ;; add users to project
      (doseq [{:keys [user-id]} [test-user-1 test-user-2]]
        (member/add-project-member project-id user-id))
      ;; users label all articles
      (doseq [{:keys [user-id]} [test-user-1 test-user-2]]
        (review-all-articles project-id user-id))
      ;; retrieve the project information from the GraphQL project and make sure it is correct
      (let [graphql-resp
            (->> (venia/graphql-query
                  {:venia/queries
                   [[:project {:id project-id}
                     [:name :id
                      [:labelDefinitions [:consensus :enabled :name :question :required :type]]
                      [:groupLabelDefinitions [:enabled :name :question :required :type [:labels [:consensus :enabled :name :question :required :type]]]]
                      [:articles [:datasource_id :enabled :id :uuid
                                  [:groupLabels [[:answer
                                                  [:id :answer :name :question :required :type]]
                                                 :confirmed :consensus :created :id :name :question :required :updated :type
                                                 [:reviewer [:id :name]]]]
                                  [:labels [:answer :confirmed :consensus :created :id :name :question :required :updated :type
                                            [:reviewer [:id :name]]]]]]]]]})
                 (graphql-request system)
                 :data)]
        ;; check project name
        (is (= project-name
               (get-in graphql-resp [:project :name])))
        ;; check label definitions
        (is (= (->> label-definitions
                    vals
                    (map #(set/rename-keys % {:value-type :type}))
                    (filter #(not= (:type %) "group"))
                    short-label->name
                    (map #(select-keys % [:consensus :enabled :name :question :required :type]))
                    set)
               (->> (get-in graphql-resp [:project :labelDefinitions])
                    set)))
        ;; check group label definitions
        (is (= (->> label-definitions
                    vals
                    (map #(set/rename-keys % {:value-type :type}))
                    (filter #(= (:type %) "group"))
                    short-label->name
                    (map #(select-keys % [:enabled :name :required :type]))
                    set)
               (->> (get-in graphql-resp [:project :groupLabelDefinitions])
                    (map #(select-keys % [:consensus :enabled :name :required :type]))
                    set)))
        ;; check the labels in the group label definitions
        (is (= (->> label-definitions
                    vals
                    (map #(set/rename-keys % {:value-type :type}))
                    (filter #(= (:type %) "group"))
                    first :labels vals
                    (map #(set/rename-keys % {:value-type :type}))
                    short-label->name
                    (map #(select-keys % [:consensus :enabled :name :question :required :type]))
                    set)
               (->> (get-in graphql-resp [:project :groupLabelDefinitions])
                    first :labels set)))
        ;; check that article count is 2
        (is (= 2
               (-> (get-in graphql-resp [:project :articles])
                   count)))
        ;; check that there are two reviewers for each article..
        ;; for the non-group labels
        (let [reviewers (->> (get-in graphql-resp [:project :articles])
                             (map :labels)
                             flatten
                             (map :reviewer))]
          (is (= 16 ;; 16 is the number if two people reviewed
                 (count reviewers)))
          ;; and there should be unique reviewers
          (is (= 2
                 (->> reviewers (map :name) set count))))
        ;; check that there are two reviewers for each article..
        ;; for the group labels
        (let [reviewers (->> (get-in graphql-resp [:project :articles])
                             (map :groupLabels)
                             flatten
                             (map :reviewer))]
          (is (= 4 ;; 16 is the number if two people reviewed
                 (count reviewers)))
          ;; and there should be unique reviewers
          (is (= 2
                 (->> reviewers (map :name) set count))))
        ;; check the non-group labels
        (let [non-group-answer->map (fn [m]
                                      (->> m
                                           (map (fn [{:keys [answer id type]}]
                                                  (hash-map (parse-uuid id)
                                                            ;; special case for boolean
                                                            (if (= type "boolean")
                                                              (-> answer first read-string)
                                                              answer))))
                                           (apply merge)))
              group-answer->map (fn [m]
                                  (->> m
                                       (map (fn [{:keys [id answer]}]
                                              (hash-map
                                               (parse-uuid id)
                                               {:labels
                                                (->> (map-indexed (fn [idx itm]
                                                                    (hash-map (str idx)
                                                                              (non-group-answer->map itm)))
                                                                  answer)
                                                     (apply merge))})))
                                       (apply merge)))
              answers (->> (get-in graphql-resp [:project :articles])
                           (map #(hash-map :labels
                                           (group-by (fn [m]
                                                       {:reviewer (:reviewer m)
                                                        :article-id (:id %)})
                                                     (:labels %))
                                           :group-labels
                                           (group-by (fn [m]
                                                       {:reviewer (:reviewer m)
                                                        :article-id (:id %)})
                                                     (:groupLabels %)))))
              non-group-answers (->> answers
                                     (map :labels)
                                     (apply merge)
                                     (medley/map-vals non-group-answer->map))
              group-answers (->> answers
                                 (map :group-labels)
                                 (apply merge)
                                 (medley/map-vals group-answer->map))
              final-answers (-> (merge-with merge non-group-answers group-answers)
                                vals)]
          ;; the answer count is correct
          (is (= 4 (count final-answers)))
          ;; each label is identical and equivalent to label answers given
          (is (= (set [(create-answer-map project-id)])
                 (-> final-answers
                     set))))))))


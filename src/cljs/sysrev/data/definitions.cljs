(ns sysrev.data.definitions
  (:require
   [re-frame.core :as re-frame :refer
    [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
   [sysrev.data.core :refer [def-data]]
   [sysrev.subs.auth :refer [have-identity?]]
   [sysrev.subs.project :refer
    [project-loaded? active-project-id have-public-labels?]]
   [sysrev.subs.review :refer [task-id]]
   [sysrev.subs.articles :refer [have-article?]]
   [sysrev.subs.members :refer [have-member-articles?]]
   [sysrev.subs.ui :refer [active-panel]]
   [sysrev.shared.transit :as sr-transit]
   [sysrev.shared.util :refer [in?]]
   [sysrev.views.panels.login :refer [have-register-project?]]
   [sysrev.views.panels.password-reset :refer [have-reset-code?]]))

;;
;; Definitions for all data items fetched from server
;;

(def-data :identity
  :loaded? have-identity?
  :uri (fn [] "/api/auth/identity")
  :process
  (fn [_ _ {:keys [identity active-project projects]}]
    {:dispatch-n
     (list [:self/set-identity identity]
           [:self/set-active-project active-project]
           [:self/set-projects projects]
           [:user/store identity])}))

(def-data :project
  :loaded? project-loaded?
  :uri (fn [] "/api/project-info")
  :prereqs (fn [] [[:identity]])
  :process
  (fn [_ _ {:keys [project users]}]
    {:dispatch-n
     (list [:project/load project]
           [:user/store-multi (vals users)])}))

(def-data :project/settings
  :loaded? project-loaded?
  :uri (fn [] "/api/project-settings")
  :prereqs (fn [] [[:identity]])
  :process
  (fn [{:keys [db]} _ {:keys [settings]}]
    (let [project-id (active-project-id db)]
      {:dispatch [:project/load-settings project-id settings]})))

(def-data :project/files
  :loaded? project-loaded?
  :uri (fn [] "/api/files")
  :prereqs (fn [] [[:identity]])
  :process
  (fn [{:keys [db]} _ result]
    (let [project-id (active-project-id db)]
      (when (vector? result)
        {:dispatch [:project/load-files project-id result]}))))

(def-data :project/public-labels
  :loaded? have-public-labels?
  :uri (fn [] "/api/public-labels")
  :prereqs (fn [] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} _ result]
    (let [result-decoded (sr-transit/decode-public-labels result)]
      {:dispatch [:project/load-public-labels result-decoded]})))

(def-data :project/project-sources
  :loaded? (constantly false)
  :uri (fn [] "/api/project-sources")
  :prereqs (fn [[:identity]])
  :process
  (fn [{:keys [db]} _ result]
    (let [project-id (active-project-id db)]
      {:dispatch [:project/load-sources project-id (:sources result)]})))

(def-data :member/articles
  :loaded? have-member-articles?
  :uri (fn [user-id] (str "/api/member-articles/" user-id))
  :prereqs (fn [user-id] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} [user-id] result]
    (let [result-decoded (sr-transit/decode-member-articles result)]
      {:dispatch [:member/load-articles user-id result-decoded]})))

(def-data :review/task
  :loaded? task-id
  :uri (fn [] "/api/label-task")
  :prereqs (fn [] [[:identity] [:project]])
  :process
  (fn [{:keys [db]} _ {:keys [article labels notes today-count] :as result}]
    (if (= result :none)
      {:dispatch [:review/load-task :none nil]}
      (cond->
          {:dispatch-n
           (list [:article/load (merge article {:labels labels :notes notes})]
                 [:review/load-task (:article-id article) today-count])}

        (= (active-panel db) [:project :review])
        (merge {:scroll-top true})))))

(def-data :article
  :loaded? have-article?
  :uri (fn [article-id] (str "/api/article-info/" article-id))
  :prereqs (fn [_] [[:identity] [:project]])
  :process
  (fn [_ [article-id] {:keys [article labels notes]}]
    {:dispatch [:article/load (merge article {:labels labels :notes notes})]}))

(def-data :register-project
  :loaded? have-register-project?
  :uri (fn [_] "/api/query-register-project")
  :prereqs (fn [_] nil)
  :content (fn [register-hash] {:register-hash register-hash})
  :process
  (fn [_ [register-hash] {:keys [project]}]
    {:dispatch-n
     (list [:register/project-id register-hash (:project-id project)]
           [:register/project-name register-hash (:name project)])}))

(def-data :password-reset
  :loaded? have-reset-code?
  :uri (fn [_] "/api/auth/lookup-reset-code")
  :prereqs (fn [_] nil)
  :content (fn [reset-code] {:reset-code reset-code})
  :process
  (fn [_ [reset-code] {:keys [email]}]
    (when email
      {:dispatch-n
       (list [:reset-password/reset-code reset-code]
             [:reset-password/email email])})))

(def-data :pubmed-search
  :loaded?
  ;; if loaded? is false, then data will be fetched from server,
  ;; otherwise, no data is fetched. It is a fn of the dereferenced
  ;; re-frame.db/app-db.
  (fn [db search-term page-number]
    (let [pmids-per-page 20
          result-count (get-in db [:data :pubmed-search search-term :count])]
      ;; the result-count hasn't been updated, so the search term results still need to
      ;; be populated
      (if (nil? result-count)
        false
        ;; the page number exists
        (if (<= page-number
                (Math/ceil (/ result-count pmids-per-page)))
          (not (empty? (get-in db [:data :pubmed-search search-term :pages page-number :pmids])))
          ;; the page number doesn't exist, retrieve nothing
          true))))

  :uri
  ;; uri is a function that returns a uri string
  (fn [] "/api/pubmed/search")

  :prereqs
  ;; a fn that returns a vector of def-data entries
  (fn [] [[:identity]])

  :content
  ;; a fn that returns a map of http parameters (in a GET context)
  ;; the parameters passed to this function are the same like in
  ;; the dispatch statement which executes the query
  ;; e.g. (dispatch [:fetch [:pubmed-query "animals" 1]])
  ;;
  ;; The data can later be retrieved using a re-frame.core/subscribe call
  ;; that is defined in in the subs/ dir in the sysrev.subs.search namespace
  ;; e.g. @(subscribe [:pubmed/search-term-result "animals"])
  (fn [search-term page-number] {:term search-term
                                 :page-number page-number})

  :process
  ;;  fn of the form: [re-frame-db query-parameters (:result response)]
  (fn [_ [search-term page-number] response]
    {:dispatch-n
     ;; this defined in events/search.cljs dir in the
     ;; sysrev.events.search namespace
     (list [:pubmed/save-search-term-results search-term page-number response])}))

(def-data :pubmed-summaries
  :loaded?
  (fn [db search-term page-number pmids]
    (let [pmids-per-page 20
          result-count (get-in db [:data :pubmed-search search-term :count])]
      (if (<= page-number
              (Math/ceil (/ result-count pmids-per-page)))
        ;; the page number exists, the results should too
        (not (empty? (get-in db [:data :pubmed-search search-term :pages page-number :summaries])))
        ;; the page number isn't in the result, retrieve nothing
        true)))

  :uri
  (fn [] "/api/pubmed/summaries")

  :prereqs
  (fn [] [[:identity]])

  :content
  (fn [search-term page-number pmids] {:pmids (clojure.string/join "," pmids)})

  :process
  (fn [_ [search-term page-number pmids] response]
    {:dispatch-n
     (list [:pubmed/save-search-term-summaries search-term page-number response])}))

(ns sysrev.data.definitions
  (:require [re-frame.core :as re-frame :refer
             [subscribe dispatch reg-sub reg-event-db reg-event-fx trim-v]]
            [sysrev.data.core :refer [def-data]]
            [sysrev.state.identity :refer [have-identity?]]
            [sysrev.state.project.data :as project-data]
            [sysrev.state.review :refer [review-task-id]]
            [sysrev.state.articles :refer [have-article?]]
            [sysrev.state.nav :refer
             [active-panel active-project-id project-url-id-loaded?]]
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
  (fn [_ _ {:keys [identity projects]}]
    {:dispatch-n
     (list [:self/set-identity identity]
           [:self/set-projects projects]
           (let [url-ids-map
                 (->> projects
                      (map (fn [{:keys [project-id url-ids]}]
                             (map (fn [u] [u project-id])
                                  url-ids)))
                      (apply concat)
                      (apply concat)
                      (apply hash-map))]
             [:load-project-url-ids url-ids-map])
           [:user/store identity])}))

(def-data :project-url-id
  :loaded? project-url-id-loaded?
  :uri (fn [url-id] "/api/lookup-project-url")
  :content (fn [url-id] {:url-id url-id})
  :prereqs (fn [url-id] [])
  :process
  (fn [_ [url-id] {:keys [project-id]}]
    (if (integer? project-id)
      {:dispatch [:load-project-url-ids {url-id project-id}]}
      {:dispatch [:load-project-url-ids {url-id nil}]})))

(def-data :project
  :loaded? project-data/project-loaded?
  :uri (fn [project-id] "/api/project-info")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity]])
  :process
  (fn [_ [project-id] {:keys [project users]}]
    {:dispatch-n
     (list
      [:project/load (merge project {:error nil})]
      [:user/store-multi (vals users)]
      (let [url-ids-map
            (->> (:url-ids project)
                 (map (fn [{:keys [url-id]}]
                        [url-id project-id]))
                 (apply concat)
                 (apply hash-map))]
        [:load-project-url-ids url-ids-map]))})
  :on-error
  (fn [{:keys [db error]} [project-id] _]
    {:dispatch [:project/load {:project-id project-id
                               :error error}]}))

(def-data :project/settings
  :loaded? project-data/project-loaded?
  :uri (fn [project-id] "/api/project-settings")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity]])
  :process
  (fn [_ [project-id] {:keys [settings]}]
    {:dispatch [:project/load-settings project-id settings]}))

(def-data :project/files
  :loaded? project-data/project-loaded?
  :uri (fn [project-id] "/api/files")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity]])
  :process
  (fn [_ [project-id] result]
    (when (vector? result)
      {:dispatch [:project/load-files project-id result]})))

(def-data :project/public-labels
  :loaded? project-data/have-public-labels?
  :uri (fn [project-id] "/api/public-labels")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process
  (fn [_ [project-id] result]
    (let [result-decoded (sr-transit/decode-public-labels result)]
      {:dispatch [:project/load-public-labels project-id result-decoded]})))

(def-data :project/sources
  :loaded? project-data/project-sources-loaded?
  :uri (fn [project-id] "/api/project-sources")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process
  (fn [_ [project-id] {:keys [sources]}]
    {:dispatch
     [:project/load-sources project-id sources]}))

(def-data :project/important-terms
  :loaded? project-data/project-important-terms-loaded?
  :uri (fn [project-id] "/api/important-terms")
  :content (fn [project-id] {:project-id project-id})
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :process
  (fn [_ [project-id] result]
    {:dispatch
     [:project/load-important-terms project-id result]}))

(def-data :member/articles
  :loaded? project-data/have-member-articles?
  :uri (fn [project-id user-id] (str "/api/member-articles/" user-id))
  :content (fn [project-id user-id] {:project-id project-id})
  :prereqs (fn [project-id user-id] [[:identity] [:project project-id]])
  :process
  (fn [_ [project-id user-id] result]
    (let [result-decoded (sr-transit/decode-member-articles result)]
      {:dispatch [:member/load-articles user-id result-decoded]})))

(def-data :review/task
  :loaded? review-task-id
  :uri (fn [project-id] "/api/label-task")
  :prereqs (fn [project-id] [[:identity] [:project project-id]])
  :content (fn [project-id] {:project-id project-id})
  :process
  (fn [{:keys [db]}
       [project-id]
       {:keys [article labels notes today-count] :as result}]
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
  :uri (fn [project-id article-id]
         (str "/api/article-info/" article-id))
  :prereqs (fn [project-id article-id] [[:identity] [:project project-id]])
  :content (fn [project-id article-id] {:project-id project-id})
  :process
  (fn [_ [project-id article-id] {:keys [article labels notes]}]
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
      ;; the result-count hasn't been updated, so the search term results
      ;; still need to be populated
      (if (nil? result-count)
        false
        ;; the page number exists
        (if (<= page-number
                (Math/ceil (/ result-count pmids-per-page)))
          (not-empty (get-in db [:data :pubmed-search search-term
                                 :pages page-number :pmids]))
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
  ;; that is defined in sysrev.state.pubmed
  ;; e.g. @(subscribe [:pubmed/search-term-result "animals"])
  (fn [search-term page-number] {:term search-term
                                 :page-number page-number})

  :process
  ;;  fn of the form: [re-frame-db query-parameters (:result response)]
  (fn [_ [search-term page-number] response]
    {:dispatch-n
     ;; this is defined in sysrev.state.pubmed
     (list [:pubmed/save-search-term-results
            search-term page-number response])}))

(def-data :pubmed-summaries
  :loaded?
  (fn [db search-term page-number pmids]
    (let [pmids-per-page 20
          result-count (get-in db [:data :pubmed-search search-term :count])]
      (if (<= page-number
              (Math/ceil (/ result-count pmids-per-page)))
        ;; the page number exists, the results should too
        (not-empty (get-in db [:data :pubmed-search search-term
                               :pages page-number :summaries]))
        ;; the page number isn't in the result, retrieve nothing
        true)))

  :uri
  (fn [] "/api/pubmed/summaries")

  :prereqs
  (fn [] [[:identity]])

  :content
  (fn [search-term page-number pmids]
    {:pmids (clojure.string/join "," pmids)})

  :process
  (fn [_ [search-term page-number pmids] response]
    {:dispatch-n
     (list [:pubmed/save-search-term-summaries
            search-term page-number response])}))

(ns sysrev.web.routes.site
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [compojure.coercions :refer [as-int]]
            [compojure.core :refer :all]
            [clj-time.core :as time]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.api :as api]
            [sysrev.db.core :as db :refer [do-query]]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.db.queries :as q]
            [sysrev.config.core :refer [env]]
            [sysrev.web.app :as app :refer [wrap-authorize current-user-id]]
            [sysrev.util :refer [should-never-happen-exception]]
            [sysrev.shared.util :refer [in? map-values index-by]]))

;; Functions defined after defroutes form
(declare public-project-summaries)

(defonce global-stats-cache (atom {:value nil, :updated nil}))
(defonce global-stats-agent (agent nil))

(defn sysrev-global-stats-impl
  "Queries database to return map of values representing site-wide metrics."
  []
  (let [labeled-articles
        (-> (q/select-project-articles nil [:%count.*])
            (merge-join [:project :p] [:= :p.project-id :a.project-id])
            (merge-where
             [:and
              [:= :p.enabled true]
              [:exists
               (-> (select :*)
                   (from [:article-label :al])
                   (where [:= :al.article-id :a.article-id])
                   (q/filter-valid-article-label true))]])
            do-query first :count)
        label-entries
        (-> (q/select-project-article-labels nil true [:%count.*])
            (merge-join [:project :p] [:= :p.project-id :a.project-id])
            (merge-where [:= :p.enabled true])
            (q/filter-valid-article-label true)
            do-query first :count)
        real-users
        (-> (select :%count.*)
            (from [:web-user :u])
            (q/filter-admin-user false)
            (merge-where
             [:and
              [:not [:like :u.email "%insilica%"]]
              [:not [:like :u.email "%sysrev%"]]
              [:not [:like :u.email "%+test%"]]])
            do-query first :count)
        real-projects
        (-> (select :pm.project-id :pm.user-id :u.email :u.permissions)
            (from [:project-member :pm])
            (join [:web-user :u] [:= :u.user-id :pm.user-id])
            (->> do-query
                 (group-by :project-id)
                 (map-values
                  (fn [entries]
                    (filter #(and (not (in? (:permissions %) "admin"))
                                  (not (re-matches #".*insilica.*" (:email %)))
                                  (not (re-matches #".*sysrev.*" (:email %)))
                                  (not (re-matches #".*\+test.*" (:email %)))
                                  (not (re-matches #".*tomluec.*" (:email %))))
                            entries)))
                 vals
                 (filter not-empty)
                 count))]
    {:labeled-articles labeled-articles
     :label-entries label-entries
     :real-users real-users
     :real-projects real-projects}))

(defn update-global-stats
  "Updates value of `global-stats-cache` by querying from database."
  []
  (reset! global-stats-cache
          {:value (sysrev-global-stats-impl) :updated (time/now)}))

(defn- loop-update-global-stats
  "Runs `update-global-stats` using agent, recursing to run again after
  the update iteration (with time delay) completes."
  [& [curval]]
  (send global-stats-agent
        (fn [_]
          (log/info "running global-stats update")
          (try
            (update-global-stats)
            ;; Delay before next update (2 hours)
            (Thread/sleep (* 1000 60 60 2))
            (catch Throwable e
              (log/warn "exception in loop-update-global-stats -- continuing")
              (log/warn (.getMessage e))
              (Thread/sleep (* 1000 60 60 2)))
            (finally
              (send global-stats-agent loop-update-global-stats)))
          true)))

(defn init-global-stats
  "Loads initial value for `global-stats-cache` and start update loop using
  background threads (agent)."
  []
  (when (nil? (:value @global-stats-cache))
    (log/info "Initializing global-stats-cache")
    (update-global-stats)
    (when (= (:profile env) :prod)
      (future (Thread/sleep (* 1000 60 15))
              (loop-update-global-stats)))))

(defn sysrev-global-stats
  "Gets value of global-stats map. This should normally return immediately with
  the cached value updated periodically via `loop-update-global-stats`; however
  if the value is outdated, this will calculate and update it before returning."
  []
  (let [now (time/now)
        timeout (time/hours 4)
        {:keys [value updated]} @global-stats-cache]
    (if (or (nil? updated) (time/before? updated (time/minus now timeout)))
      (do (update-global-stats)
          (:value @global-stats-cache))
      value)))

(defroutes site-routes
  (GET "/api/global-stats" request
       {:stats (sysrev-global-stats)})

  (POST "/api/delete-user" request
        (wrap-authorize
         request {:developer true}
         (let [{{:keys [verify-user-id]
                 :as body} :body} request
               user-id (current-user-id request)
               {:keys [permissions]} (users/user-identity-info user-id)]
           (assert (= user-id verify-user-id) "verify-user-id mismatch")
           (when-not (in? permissions "admin")
             (throw (should-never-happen-exception)))
           (users/delete-user user-id)
           (with-meta
             {:success true}
             {:session {}}))))

  (POST "/api/clear-query-cache" request
        (wrap-authorize
         request {:developer true}
         (db/clear-query-cache)
         {:success true}))

  (POST "/api/change-user-settings" request
        (wrap-authorize
         request {:logged-in true}
         (let [user-id (current-user-id request)
               {:keys [changes]} (:body request)]
           (doseq [{:keys [setting value]} changes]
             (users/change-user-setting
              user-id (keyword setting) value))
           {:success true, :settings (users/user-settings user-id)})))

  (GET "/api/terms-of-use.md" request
       (app/text-file-response
        (-> (io/resource "terms_of_use.md") io/reader)
        "terms-of-use.md"))

  (GET "/api/search" [q p :<< as-int]
       (api/search-site q p)))

(defn public-project-summaries
  "Returns a sequence of summary maps for every project."
  []
  (let [admins (-> (select :u.user-id :u.email :m.permissions :m.project-id)
                   (from [:project-member :m])
                   (join [:web-user :u] [:= :u.user-id :m.user-id])
                   (->> do-query
                        (group-by :project-id)
                        (map-values (fn [members]
                                      (->> members
                                           (filter #(in? (:permissions %) "admin"))
                                           (mapv #(dissoc % :project-id)))))))]
    (-> (select :*)
        (from :project)
        (->> do-query
             (index-by :project-id)
             (map-values #(assoc % :admins (get admins (:project-id %) [])))))))

(ns sysrev.web.routes.api
  (:require [compojure.core :refer :all]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.web.app :refer
             [wrap-permissions current-user-id active-project]]
            [sysrev.util :refer
             [integerify-map-keys uuidify-map-keys]]
            [clojure.string :as str]
            [sysrev.config.core :refer [env]]
            [org.httpkit.client :as client]
            [clojure.data.json :as json]))

(defmacro check-allow-answers
  "Returns an error response if the project-id of request contains any user
  label answers, unless the request passed value true for :allow-answers arg."
  [request & body]
  `(let [request# ~request
         body-fn# #(do ~@body)
         allow-answers# (-> request# :body :allow-answers)
         project-id# (-> request# :body :project-id)
         answers-count# (-> (q/select-project-article-labels project-id# nil [:%count.*])
                            do-query first :count)]
     (assert project-id#)
     (if (and (not (true? allow-answers#))
              (not= 0 answers-count#))
       {:error
        {:status 403
         :type :api
         :message
         (->> [(format "project %s contains user answers to labels (count=%d)"
                       project-id# answers-count#)
               "To confirm this is intended, include argument \"allow-answers\" with true value in your request."]
              (str/join "\n"))}}
       (body-fn#))))

(defroutes api-routes
  (GET "/web-api/get-api-token" request
       (let [request (dissoc request :session)
             {:keys [email password] :as body} (:body request)
             valid (users/valid-password? email password)
             user (when valid (users/get-user-by-email email))
             {verified :verified :or {verified false}} user
             success (boolean (and valid verified))]
         (if success
           {:success true
            :api-token (:api-token user)}
           {:error
            {:status 403
             :type :api
             :message "User authentication failed"}})))
  (POST "/web-api/import-pmids" request
        (let [request (dissoc request :session)]
          (wrap-permissions
           request ["admin"] []
           (check-allow-answers
            request
            (let [{:keys [api-token project-id pmids allow-answers] :as body}
                  (-> request :body)
                  project (q/query-project-by-id project-id [:*])]
              (cond
                (nil? project)
                {:error {:status 500
                         :type :api
                         :message
                         (format "project not found (id=%s)" project-id)}}
                (or (not (seqable? pmids))
                    (empty? pmids)
                    (not (every? integer? pmids)))
                {:error {:status 500
                         :type :api
                         :message "pmids must be an array of integers"}}
                :else
                (do (pubmed/import-pmids-to-project pmids project-id)
                    {:result
                     {:success true
                      :project-articles
                      (project/project-article-count project-id)}}))))))))

;; HTTP client functions for testing API handlers
(defn webapi-request [method route body & {:keys [host port url]}]
  (let [port (or port (-> env :server :port))
        host (or host "localhost")]
    (-> @(client/request
          {:url (if url
                  (format "%sweb-api/%s" url route)
                  (format "http://%s:%d/web-api/%s"
                          host port route))
           :method method
           :body (json/write-str body)
           :headers {"Content-Type" "application/json"}})
        :body (json/read-str :key-fn keyword))))
(defn webapi-get [route body & opts]
  (apply webapi-request :get route body opts))
(defn webapi-post [route body & opts]
  (apply webapi-request :post route body opts))

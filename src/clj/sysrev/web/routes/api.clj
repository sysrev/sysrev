(ns sysrev.web.routes.api
  (:require [compojure.core :refer :all]
            [sysrev.shared.util :refer [map-values in?]]
            [sysrev.db.core :refer [do-query do-execute]]
            [sysrev.db.queries :as q]
            [sysrev.db.users :as users]
            [sysrev.db.project :as project]
            [sysrev.import.pubmed :as pubmed]
            [sysrev.util :refer
             [integerify-map-keys uuidify-map-keys]]))

(defroutes api-routes
  (POST "/web-api/import-pmids" request
        (let [{:keys [api-token project-id pmids] :as body}
              (-> request :body)
              user (users/get-user-by-api-token api-token)
              project (q/query-project-by-id project-id [:*])]
          (cond
            (nil? user)
            {:error {:status 403
                     :type :user
                     :message "Invalid api-token"}}
            (not (in? (:permissions user) "admin"))
            {:error {:status 403
                     :type :user
                     :message "User does not have admin permissions"}}
            (nil? project)
            {:error {:status 500
                     :type :api
                     :message "Invalid project-id"}}
            (or (not (seqable? pmids))
                (empty? pmids)
                (not (every? integer? pmids)))
            {:error {:status 500
                     :type :api
                     :message "pmids must be an array of integers"}}
            true
            (do (pubmed/import-pmids-to-project pmids project-id)
                {:result {:success true}})))))

(ns sysrev.web.routes.graphql
  (:require [compojure.core :refer [defroutes GET POST]]
            [sysrev.graphql.handler :refer [graphql-handler sysrev-schema]]))

(defroutes graphql-routes
  (GET "/graphql" request ((graphql-handler (sysrev-schema)) request))
  (POST"/graphql" request ((graphql-handler (sysrev-schema)) request)))

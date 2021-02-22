(ns sysrev.graphql.authorization
  (:require [com.walmartlabs.lacinia.resolve :refer [resolve-as ResolverResult]]
            [sysrev.datasource.api :as ds-api]
            [sysrev.payment.stripe :as stripe]
            [sysrev.user.core :refer [user-by-api-token user-settings]]))

(defn fail [message & [more]]
  (resolve-as false [(cond-> {:message message}
                       more (merge more))]))

(defn ^ResolverResult authorized? [_query _vars {:keys [authorization]}]
  (let [user (some-> authorization (user-by-api-token))
        user-id (:user-id user)
        dev-account-enabled? (:dev-account-enabled? (user-settings user-id))]
    (cond (not (seq authorization))
          (fail "api-token not supplied in request headers as Authorization: Bearer <api-token>")
          ;; this to allow sysrev to make queries on itself
          (= authorization (ds-api/ds-auth-key))
          (resolve-as true true)
          (not (stripe/user-has-pro? user-id))
          (fail "user does not have a have pro account")
          (not user-id)
          (fail "api-token is not associated with a user")
          (not dev-account-enabled?)
          (fail "dev account is not enabled for that user")
          :else (resolve-as true true))))


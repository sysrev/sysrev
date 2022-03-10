(ns sysrev.user.interface
  (:require [sysrev.user.core :as user]))

(defn change-username
  "Change the user's username. Return the number of rows modified."
  [user-id new-username]
  (user/change-username user-id new-username))

(defn change-user-setting [user-id setting new-value]
  (user/change-user-setting user-id setting new-value))

(defn clear-password-reset-code [user-id]
  (user/clear-password-reset-code user-id))

(defn create-email-verification!
  "Create a DB record with a random verification code."
  [user-id email & {:keys [principal] :or {principal false}}]
  (user/create-email-verification! user-id email :principal principal))

(defn create-password-reset-code [user-id]
  (user/create-password-reset-code user-id))

(defn create-user [email password & {:keys [google-user-id permissions]
                                     :or {permissions ["user"]}
                                     :as opts}]
  (apply user/create-user email password
         (apply concat opts)))

(defn create-user-stripe
  "Create a DB record relating stripe-acct to user-id."
  [stripe-acct user-id]
  (user/create-user-stripe stripe-acct user-id))

(defn create-user-stripe-customer!
  "Create a stripe customer from user"
  [user]
  (user/create-user-stripe-customer! user))

(defn current-email-entry [user-id email]
  (user/current-email-entry user-id email))

(defn delete-user [user-id]
  (user/delete-user user-id))

(defn dev-user? [user-id]
  (user/dev-user? user-id))

(defn email-verify-code
  "Return the verification code for this user-id and email, if one exists."
  [user-id email]
  (user/email-verify-code user-id email))

(defn get-user-emails [user-id]
  (user/get-user-emails user-id))

(defn get-users-public-info
  "Given a coll of user-ids, return a coll of maps that represent the
  publicly viewable information for each user-id"
  [user-ids]
  (user/get-users-public-info user-ids))

(defn primary-email-verified?
  "Is this user's primary email verified?"
  [user-id]
  (user/primary-email-verified? user-id))

(defn projects-annotated-summary
  "Return the count of annotations done by user-id grouped by projects"
  [user-id]
  (user/projects-annotated-summary user-id))

(defn projects-labeled-summary
  "Return the count of articles and labels done by user-id grouped by projects"
  [user-id]
  (user/projects-labeled-summary user-id))

(defn search-users
  "Return users whose username matches q"
  [q & {:keys [limit] :or {limit 5}}]
  (user/search-users q :limit limit))

(defn set-primary-email!
  "Given an email, set it as the primary email address for user-id. This
  assumes that the email address has been confirmed"
  [user-id email]
  (user/set-primary-email! user-id email))

(defn set-user-email-enabled! [user-id email enabled]
  (user/set-user-email-enabled! user-id email enabled))

(defn set-user-password [email new-password]
  (user/set-user-password email new-password))

(defn update-member-access-time
  "Update the last time the user accessed the project."
  [user-id project-id]
  (user/update-member-access-time user-id project-id))

(defn update-user-introduction! [user-id introduction]
  (user/update-user-introduction! user-id introduction))

(defn user-by-api-token [api-token]
  (user/user-by-api-token api-token))

(defn user-by-email [email & [fields]]
  (user/user-by-email email fields))

(defn user-by-id [user-id]
  (user/user-by-id user-id))

(defn user-by-reset-code [reset-code]
  (user/user-by-reset-code reset-code))

(defn user-by-username [username]
  (user/user-by-username username))

(defn user-email-status
  "Return true if the user has verified with this verify-code, false if the
  verify-code exists but has not been used, or nil if the verify-code does
  not exist."
  [user-id verify-code]
  (user/user-email-status user-id verify-code))

(defn user-id-from-url-id
  "Not really implemented - just a parse-integer call."
  [url-id]
  (user/user-id-from-url-id url-id))

(defn user-identity-info
  "Returns basic identity info for user."
  [user-id]
  (user/user-identity-info user-id))

(defn user-owned-projects
  "Returns sequence of projects which are owned by user-id.
  (see user-projects)"
  [user-id & [fields]]
  (user/user-owned-projects user-id fields))

(defn user-password-reset-url
  [user-id & {:keys [url-base] :or {url-base "https://sysrev.com"}}]
  (user/user-password-reset-url user-id :url-base url-base))

(defn user-projects
  "Returns sequence of projects for which user-id is a
  member. Includes :project-id by default; fields optionally specifies
  additional fields from [:project :p] or [:project-member :pm]."
  [user-id & [fields]]
  (user/user-projects user-id fields))

(defn user-public-projects
  "Returns sequence of public projects for which user-id is a member.
  (see user-projects)"
  [user-id & [fields]]
  (user/user-public-projects user-id fields))

(defn user-self-info
  "Returns a map of values with various user account information.
  This result is sent to client for the user's own account upon login."
  [user-id]
  (user/user-self-info user-id))

(defn user-settings [user-id]
  (user/user-settings user-id))

(defn user-stripe-account [user-id]
  (user/user-stripe-account user-id))

(defn valid-password?
  "Does this password match the stored hash for the user with this email?"
  [email password-attempt]
  (user/valid-password? email password-attempt))

(defn verified-primary-email?
  "Is this email already primary and verified?"
  [email]
  (user/verified-primary-email? email))

(defn verify-email!
  "Record the email as verified with this verify-code. The verify-code must exist."
  [email verify-code user-id]
  (user/verify-email! email verify-code user-id))

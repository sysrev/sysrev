(ns sysrev.db.users
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            buddy.hashers
            crypto.random
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [honeysql.core :as sql]
            [honeysql.helpers :as sqlh :refer :all :exclude [update]]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [sysrev.config.core :refer [env]]
            [sysrev.db.core :as db :refer
             [do-query do-execute with-transaction sql-now raw-query]]
            [sysrev.db.queries :as q]
            [sysrev.db.project :refer [add-project-member]]
            [sysrev.shared.spec.core :as sc]
            [sysrev.shared.spec.users :as su]
            [sysrev.stripe :as stripe]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil :refer [map-values in?]])
  (:import java.util.UUID))

(defn all-users
  "Returns seq of short info on all users, for interactive use."
  []
  (-> (select :*) (from :web-user) do-query
      (->> (map #(select-keys % [:user-id :email :permissions])))))

(defn get-user-by-email [email]
  (-> (select :*)
      (from :web-user)
      (where [:= (sql/call :lower :email) (sql/call :lower email)])
      do-query first))

(defn get-user-by-id [user-id]
  (-> (select :*)
      (from :web-user)
      (where [:= :user-id user-id])
      do-query first))

(defn user-projects
  "Returns sequence of projects for which user-id is a
  member. Includes :project-id by default; fields optionally specifies
  additional fields from [:project :p] or [:project-member :pm]."
  [user-id & [fields]]
  (-> (apply select :p.project-id fields)
      (from [:project :p])
      (join [:project-member :pm] [:= :pm.project-id :p.project-id])
      (where [:and [:= :p.enabled true] [:= :pm.user-id user-id]])
      do-query))

(defn user-public-projects
  "Returns sequence of public projects for which user-id is a member.
  (see user-projects)"
  [user-id & [fields]]
  (->> (user-projects user-id (conj fields :p.settings))
       (filter #(-> % :settings :public-access true?))))

(defn user-owned-projects
  "Returns sequence of projects which are owned by user-id.
  (see user-projects)"
  [user-id & [fields]]
  (->> (user-projects user-id (conj fields :pm.permissions))
       (filter #(in? (:permissions %) "owner"))))

(defn clear-user-cache
  "Clears cache for projects that may be affected by a change to user-id."
  [user-id]
  (doseq [{:keys [project-id]} (user-projects user-id)]
    (db/clear-project-cache project-id)))

(defn get-users-public-info
  "Given a coll of user-ids, return a coll of maps that represent the
  publicly viewable information for each user-id"
  [user-ids]
  (when (seq user-ids)
    (-> (select :user-id :email :date-created :username :introduction)
        (from :web-user)
        (where [:in :web-user.user-id user-ids])
        (->> do-query (map #(-> (dissoc % :email)
                                (assoc :username (first (str/split (:email %) #"@")))))))))

(defn get-user-by-reset-code [reset-code]
  (assert (string? reset-code))
  (-> (select :*)
      (from :web-user)
      (where [:= :reset-code reset-code])
      do-query
      first))

(defn get-user-by-api-token [api-token]
  (assert (string? api-token))
  (-> (select :*)
      (from :web-user)
      (where [:= :api-token api-token])
      do-query first))

(defn generate-api-token []
  (->> (crypto.random/bytes 16)
       hash/sha256
       vec
       (take 12)
       byte-array
       codecs/bytes->hex))

(defn encrypt-password [password]
  (buddy.hashers/encrypt
   password {:algorithm :bcrypt+sha512
             :iterations 6
             :salt (crypto.random/bytes 16)}))

(defn create-user [email password & {:keys [project-id user-id permissions]
                                     :or {permissions ["user"]}
                                     :as opts}]
  (let [test-email? (and (not= (:profile env) :prod)
                         (boolean (or (re-find #"\+test.*\@" email)
                                      (re-find #"\@sysrev\.us$" email)
                                      (re-find #"\@insilica\.co$" email))))
        permissions (cond (:permissions opts)  (:permissions opts)
                          test-email?          ["admin"]
                          :else                permissions)
        entry (cond-> {:email email
                       :pw-encrypted-buddy (encrypt-password password)
                       :verify-code nil ;; (crypto.random/hex 16)
                       :permissions (db/to-sql-array "text" permissions)
                       :default-project-id project-id
                       :date-created (sql-now)
                       :user-uuid (UUID/randomUUID)
                       :api-token (generate-api-token)}
                user-id (assoc :user-id user-id))]
    (when project-id (assert (q/query-project-by-id project-id [:project-id])))
    (let [{:keys [user-id] :as user} (-> (insert-into :web-user)
                                         (values [entry])
                                         (returning :*)
                                         do-query first)]
      (when project-id (add-project-member project-id user-id))
      user)))

(defn set-user-password [email new-password]
  (-> (sqlh/update :web-user)
      (sset {:pw-encrypted-buddy (encrypt-password new-password)})
      (where [:= (sql/call :lower :email) (sql/call :lower email)])
      do-execute))

(defn set-user-permissions
  "Change the site permissions for a user account."
  [user-id permissions]
  (try (-> (sqlh/update :web-user)
           (sset {:permissions (db/to-sql-array "text" permissions)})
           (where [:= :user-id user-id])
           (returning :user-id :permissions)
           do-query)
       (finally (clear-user-cache user-id))))

(defn valid-password? [email password-attempt]
  (let [entry (get-user-by-email email)
        encrypted-password (:pw-encrypted-buddy entry)]
    (boolean (and entry encrypted-password
                  (buddy.hashers/check password-attempt encrypted-password)))))

(defn delete-user [user-id]
  (assert (integer? user-id))
  (try (q/delete-by-id :web-user :user-id user-id)
       (finally (db/clear-query-cache))))

(defn delete-user-by-email [email]
  (assert (string? email))
  (try (q/delete-by-id :web-user :email email)
       (finally (db/clear-query-cache))))

(defn create-password-reset-code [user-id]
  (-> (sqlh/update :web-user)
      (sset {:reset-code (crypto.random/hex 16)})
      (where [:= :user-id user-id])
      do-execute))

(defn clear-password-reset-code [user-id]
  (-> (sqlh/update :web-user)
      (sset {:reset-code nil})
      (where [:= :user-id user-id])
      do-execute))

(defn user-password-reset-url
  [user-id & {:keys [url-base] :or {url-base "https://sysrev.com"}}]
  (when-let [reset-code (-> (select :reset-code)
                            (from :web-user)
                            (where [:= :user-id user-id])
                            do-query first :reset-code)]
    (format "%s/reset-password/%s" url-base reset-code)))

(defn user-settings [user-id]
  (into {} (-> (select :settings)
               (from :web-user)
               (where [:= :user-id user-id])
               do-query first :settings)))

(defn change-user-setting [user-id setting new-value]
  (with-transaction
    (let [cur-settings (-> (select :settings)
                           (from :web-user)
                           (where [:= :user-id user-id])
                           do-query first :settings)
          new-settings (assoc cur-settings setting new-value)]
      (assert (s/valid? ::su/settings new-settings))
      (-> (sqlh/update :web-user)
          (sset {:settings (db/to-jsonb new-settings)})
          (where [:= :user-id user-id])
          do-execute)
      new-settings)))

(defn user-identity-info
  "Returns basic identity info for user."
  [user-id & [self?]]
  (-> (select :user-id :user-uuid :email :verified :permissions :settings :default-project-id)
      (from :web-user)
      (where [:= :user-id user-id])
      do-query first))

(defn user-self-info
  "Returns a map of values with various user account information.
  This result is sent to client for the user's own account upon login."
  [user-id]
  (let [uperms (:permissions (get-user-by-id user-id))
        admin? (in? uperms "admin")
        project-url-ids (-> (select :purl.url-id :purl.project-id)
                            (from [:project-member :m])
                            (join [:project :p]
                                  [:= :p.project-id :m.project-id])
                            (merge-join [:project-url-id :purl]
                                        [:= :purl.project-id :p.project-id])
                            (where [:and
                                    [:= :m.user-id user-id]
                                    [:= :p.enabled true]
                                    [:= :m.enabled true]])
                            (order-by [:purl.date-created :desc])
                            (->> do-query
                                 (group-by :project-id)
                                 (map-values #(mapv :url-id %))))
        projects (-> (select :p.project-id :p.name :p.date-created
                             :m.join-date :m.access-date
                             [:p.enabled :project-enabled]
                             [:m.enabled :member-enabled]
                             [:m.permissions :permissions])
                     (from [:project-member :m])
                     (join [:project :p]
                           [:= :p.project-id :m.project-id])
                     (where [:and
                             [:= :m.user-id user-id]
                             [:= :p.enabled true]
                             [:= :m.enabled true]])
                     (order-by [:m.access-date :desc :nulls :last]
                               [:p.date-created])
                     (->> do-query
                          (sort-by (fn [{:keys [access-date date-created]}]
                                     [(or (some-> access-date tc/to-epoch) 0)
                                      (- (tc/to-epoch date-created))]))
                          reverse
                          (mapv #(-> % (assoc :member? true
                                              :url-ids (get project-url-ids (:project-id %)))))))
        self-project-ids (->> projects (map :project-id))
        all-projects (when admin?
                       (-> (select :p.project-id :p.name :p.date-created
                                   [:p.enabled :project-enabled])
                           (from [:project :p])
                           (where [:and [:= :p.enabled true]])
                           (order-by :p.date-created)
                           (->> do-query
                                (filterv #(not (in? self-project-ids (:project-id %))))
                                (mapv #(assoc % :member? false
                                              :url-ids (get project-url-ids
                                                            (:project-id %)))))))]
    {:projects (->> [projects all-projects] (apply concat) vec)}))

(defn create-sysrev-stripe-customer!
  "Create a stripe customer from user"
  [user]
  (let [{:keys [email user-uuid user-id]} user
        stripe-response (stripe/create-customer! :email email
                                                 :description (str "Sysrev UUID: " user-uuid))
        stripe-customer-id (:id stripe-response)]
    (if-not (nil? stripe-customer-id)
      (try (do (-> (sqlh/update :web-user)
                   (sset {:stripe-id stripe-customer-id})
                   (where [:= :user-id user-id])
                   do-execute)
               {:success true})
           (catch Throwable e
             {:error {:message "Exception in create-sysrev-stripe-customer!"
                      :exception e}}))
      {:error {:message (str "No customer id returned by stripe.com for email: "
                             email " and uuid: " user-uuid)}})))

;; for testing purposes
(defn delete-sysrev-stripe-customer!
  [user]
  (with-transaction
    (let [{:keys [email user-uuid user-id stripe-id]} user
          stripe-source-id (-> (stripe/read-default-customer-source stripe-id) :id)]
      (when stripe-source-id
        (stripe/delete-customer-card! stripe-id stripe-source-id))
      (-> (sqlh/update :web-user)
          (sset {:stripe-id nil})
          (where [:= :user-id user-id])
          do-execute))))

(defn set-user-default-project [user-id project-id]
  (-> (sqlh/update :web-user)
      (sset {:default-project-id project-id})
      (where [:= :user-id user-id])
      do-execute))

(defn update-member-access-time [user-id project-id]
  (-> (sqlh/update :project-member)
      (sset {:access-date (sql-now)})
      (where [:and
              [:= :user-id user-id]
              [:= :project-id project-id]])
      do-execute))

(defn create-web-user-stripe-acct [stripe-acct user-id]
  (-> (insert-into :web-user-stripe-acct)
      (values [{:stripe-acct stripe-acct
                :user-id user-id}])
      do-execute))

(defn user-stripe-account
  [user-id]
  (-> (select :*)
      (from :web-user-stripe-acct)
      (where [:= :user-id user-id])
      do-query first))

(defn verified-primary-email?
  "Is this email already primary and verified?"
  [email]
  (-> (select :email)
      (from :web-user-email)
      (where [:and
              [:= :email email]
              [:= :principal true]
              [:= :verified true]])
      do-query empty? not))

(defn create-email-verification!
  [user-id email & {:keys [principal] :or {principal false}}]
  (-> (insert-into :web-user-email)
      (values [{:user-id user-id
                :email email
                :verify-code (crypto.random/hex 16)
                :principal principal}])
      do-execute))

(defn web-user-email
  [user-id verify-code]
  (-> (select :verify-code :verified :email)
      (from :web-user-email)
      (where [:and
              [:= :user-id user-id]
              [:= :verify-code verify-code]])
      do-query first))

(defn current-email-entry
  [user-id email]
  (-> (select :*)
      (from :web-user-email)
      (where [:and
              [:= :user-id user-id]
              [:= :email email]])
      do-query first))

(defn primary-email-verified?
  "Is the primary email for this user verified?"
  [user-id]
  (let [{:keys [email]} (get-user-by-id user-id)]
    (-> (select :verified)
        (from :web-user-email)
        (where [:and
                [:= :user-id user-id]
                [:= :email email]
                [:= :principal true]])
        do-query first :verified boolean)))

(defn set-primary-email!
  "Given an email, set it as the primary email address for user-id. This assumes that the email address has been confirmed"
  [user-id email]
  ;; set all web-user-email principal to false for this user-id
  ;; note: this will eventually change
  (with-transaction
    (-> (sqlh/update :web-user-email)
        (sset {:principal false
               :updated (sql-now)})
        (where [:= :user-id user-id])
        do-execute)
    (-> (sqlh/update :web-user-email)
        (sset {:principal true
               :updated (sql-now)})
        (where [:and
                [:= :user-id user-id]
                [:= :email email]])
        do-execute)
    ;; update the user
    (-> (sqlh/update :web-user)
        (sset {:verified true, :email email})
        (where [:= :user-id user-id])
        do-execute)))

(defn verify-email! [email verify-code user-id]
  (-> (sqlh/update :web-user-email)
      (sset {:verified true, :updated (sql-now)})
      (where [:and
              (if verify-code
                [:= :verify-code verify-code]
                true)
              [:= :email email]
              [:= :user-id user-id]])
      do-execute))

(defn read-email-addresses
  [user-id]
  (-> (select :*)
      (from :web-user-email)
      (where [:and [:= :user-id user-id] [:= :active true]])
      do-query))

(defn set-active-field-email!
  [user-id email active]
  (-> (sqlh/update :web-user-email)
      (sset {:active active})
      (where [:and [:= :email email] [:= :user-id user-id]])
      do-execute))

(defn read-email-verification-code
  [user-id email]
  (-> (select :verify-code)
      (from :web-user-email)
      (where [:and [:= :user-id user-id] [:= :email email]])
      do-query first))

(defn projects-labeled-summary
  "Return the count of articles and labels done by user-id grouped by projects"
  [user-id]
  (-> (select [:%count.%distinct.al.article-id :articles]
              [:%count.al.article-label-id :labels]
              [:a.project-id :project-id])
      (from [:article-label :al])
      (join [:article :a] [:= :a.article-id :al.article-id])
      (where [:and [:= :al.user-id user-id] [:= :a.enabled true]])
      (group :a.project-id)
      do-query))

(defn projects-annotated-summary
  "Return the count of annotations done by user-id grouped by projects"
  [user-id]
  (-> (select [:%count.an.id :annotations] [:a.project-id :project-id])
      (from [:annotation :an])
      (join [:annotation-article :aa] [:= :aa.annotation-id :an.id]
            [:article :a] [:= :a.article-id :aa.article-id]
            [:annotation-web-user :awu] [:= :awu.annotation-id :an.id])
      (left-join [:annotation-s3store :as3] [:= :an.id :as3.annotation-id]
                 [:s3store :s3] [:= :s3.id :as3.s3store-id]
                 [:annotation-web-user :au] [:= :au.annotation-id :an.id]
                 [:annotation-semantic-class :asc] [:= :an.id :asc.annotation-id]
                 [:semantic-class :sc] [:= :sc.id :asc.semantic-class-id])
      (group :a.project-id)
      (where [:= :awu.user-id user-id])
      do-query))

(defn update-user-introduction!
  [user-id introduction]
  (-> (sqlh/update :web-user)
      (sset {:introduction introduction})
      (where [:= :user-id user-id])
      do-execute))

(defn search-users
  "Return users whose email matches term"
  [term]
  (with-transaction
    (let [user-ids (->> ["SELECT user_id FROM web_user WHERE (email ilike ?) ORDER BY email LIMIT ?"
                         (str term "%")
                         5]
                        db/raw-query
                        (map :user-id))
          ;; original query, except using ilike instead of like for case insensitivity
          #_(-> (select :user-id)
                (from :web-user)
                (where [:like :email (str term "%")])
                (order-by :email)
                ;; don't want to overwhelm with options
                (limit 5)
                (sql/format))]
      ;; check to see if we have results before returning the public info
      (if (empty? user-ids)
        user-ids
        (get-users-public-info user-ids)))))

(defn user-id-from-url-id [url-id]
  ;; TODO: implement url-id strings for users
  (sutil/parse-integer url-id))

(ns sysrev.user.core
  (:require [buddy.core.codecs :as codecs]
            [buddy.core.hash :as hash]
            buddy.hashers
            [clj-time.coerce :as tc]
            [clojure.set :refer [rename-keys]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            crypto.random
            [honeysql.core :as sql]
            [honeysql.helpers
             :as
             sqlh
             :refer
             [from join merge-join order-by select where]]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.core :as db :refer [do-query sql-now with-transaction]]
            [sysrev.db.queries :as q]
            [sysrev.payment.stripe :as stripe]
            [sysrev.project.core :as project]
            [sysrev.project.member :refer [add-project-member]]
            [sysrev.user.interface.spec :as su]
            [sysrev.util :as util :refer [in? map-values]])
  (:import java.util.UUID))

(defn ^:repl all-users
  "Returns seq of short info on all users, for interactive use."
  []
  (->> (q/find :web-user {})
       (map #(select-keys % [:user-id :email :permissions]))))

(defn user-by-email [email & [fields]]
  (q/find-one :web-user {} (or fields :*)
              :where [:= (sql/call :lower :email) (sql/call :lower email)]))

(defn user-projects
  "Returns sequence of projects for which user-id is a
  member. Includes :project-id by default; fields optionally specifies
  additional fields from [:project :p] or [:project-member :pm]."
  [user-id & [fields]]
  (q/find [:project :p] {:p.enabled true, :pm.user-id user-id}
          (concat [:p.project-id] fields)
          :join [[:project-member :pm] :p.project-id]))

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
    (->> (q/find :web-user {:user-id user-ids}
                 [:user-id :email :date-created :username :introduction])
         (map #(-> (dissoc % :email)
                   (assoc :username (first (str/split (:email %) #"@"))))))))

(defn user-by-reset-code [reset-code]
  (q/find-one :web-user {:reset-code reset-code}))

(defn user-by-api-token [api-token]
  (q/find-one :web-user {:api-token api-token}))

(defn user-by-id [user-id]
  (q/find-one :web-user {:user-id user-id}))

(defn-spec user-by-username (s/nilable ::su/user)
  [username ::su/username]
  (q/find-one :web-user
              {[:lower :username] (str/lower-case username)}))

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

(defn-spec unique-username ::su/username
  [email ::su/email]
  (with-transaction
    (let [s (-> (str/split email #"@" 2)
                first
                (str/replace #"[^A-Za-z0-9]+" "-"))]
      (if-not (user-by-username s)
        s
        (loop [sfx (str (rand-int 10))]
          (let [t (str s \- sfx)]
            (if (>= 40 (count t))
              (if-not (user-by-username t)
                t
                (recur (str sfx (rand-int 10))))
              (str (UUID/randomUUID)))))))))

(defn create-user [email password & {:keys [project-id user-id permissions google-user-id]
                                     :or {permissions ["user"]}}]
  (with-transaction
    (let [user (q/create :web-user (cond-> {:email email
                                            :username (unique-username email)
                                            :verify-code nil ;; (crypto.random/hex 16)
                                            :permissions (db/to-sql-array "text" permissions)
                                            :date-created :%now
                                            :user-uuid (UUID/randomUUID)
                                            :api-token (generate-api-token)}
                                     user-id (assoc :user-id user-id)
                                     password (assoc :pw-encrypted-buddy
                                                     (encrypt-password password))
                                     google-user-id (assoc :google-user-id google-user-id
                                                           :registered-from "google"
                                                           :date-google-login :%now))
                         :returning :*)]
      (when project-id (add-project-member project-id (:user-id user)))
      user)))

(defn set-user-password [email new-password]
  (q/modify :web-user {} {:pw-encrypted-buddy (encrypt-password new-password)}
            :where [:= (sql/call :lower :email) (sql/call :lower email)]))

(defn set-user-permissions
  "Change the site permissions for a user account."
  [user-id permissions]
  (try (q/modify :web-user {:user-id user-id}
                 {:permissions (db/to-sql-array "text" permissions)}
                 :returning [:user-id :permissions])
       (finally (clear-user-cache user-id))))

(defn valid-password? [email password-attempt]
  (let [entry (user-by-email email)
        encrypted-password (:pw-encrypted-buddy entry)]
    (boolean (and entry encrypted-password
                  (buddy.hashers/check password-attempt encrypted-password)))))

(defn delete-user [user-id]
  (assert (integer? user-id))
  (let [project-ids (q/find :project-member {:user-id user-id} :project-id)]
    (q/delete :web-user {:user-id user-id})
    (doseq [project-id project-ids] (db/clear-project-cache project-id))))

(defn delete-user-by-email [email]
  (assert (string? email))
  (some-> (q/find-one :web-user {:email email} :user-id)
          (delete-user)))

(defn create-password-reset-code [user-id]
  (q/modify :web-user {:user-id user-id} {:reset-code (crypto.random/hex 16)}))

(defn clear-password-reset-code [user-id]
  (q/modify :web-user {:user-id user-id} {:reset-code nil}))

(defn user-password-reset-url
  [user-id & {:keys [url-base] :or {url-base "https://sysrev.com"}}]
  (when-let [reset-code (q/get-user user-id :reset-code)]
    (format "%s/reset-password/%s" url-base reset-code)))

(defn user-settings [user-id]
  (into {} (q/get-user user-id :settings)))

(defn-spec change-username nat-int?
  [user-id ::su/user-id new-username ::su/username]
  (with-transaction
    (q/modify :web-user {:user-id user-id} {:username new-username})))

(defn change-user-setting [user-id setting new-value]
  (with-transaction
    (let [cur-settings (q/get-user user-id :settings)
          new-settings (assoc cur-settings setting new-value)]
      (assert (s/valid? ::su/settings new-settings))
      (q/modify :web-user {:user-id user-id} {:settings (db/to-jsonb new-settings)})
      new-settings)))

(defn user-identity-info
  "Returns basic identity info for user."
  [user-id & [_self?]]
  (-> (q/get-user user-id [:user-id :user-uuid :email :verified :permissions :settings :api-token])
      (rename-keys {:api-token :api-key})))

(defn user-self-info
  "Returns a map of values with various user account information.
  This result is sent to client for the user's own account upon login."
  [user-id]
  (let [project-url-ids (-> (select :purl.url-id :purl.project-id)
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
                             [:m.permissions :permissions]
                             [:p.settings :settings])
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
                          (mapv #(-> %
                                     (assoc :member? true
                                            :url-ids (get project-url-ids (:project-id %))
                                            :public-access (-> % :settings :public-access)
                                            :project-owner (project/get-project-owner (:project-id %)))
                                     (dissoc :settings)))))]
    {:projects (->> [projects] (apply concat) vec)}))

(defn create-user-stripe-customer!
  "Create a stripe customer from user"
  [user]
  (let [{:keys [email user-uuid user-id]} user
        stripe-response (stripe/create-customer! :email email
                                                 :description (str "Sysrev UUID: " user-uuid))
        stripe-customer-id (:id stripe-response)]
    (if-not (nil? stripe-customer-id)
      (try (q/modify :web-user {:user-id user-id} {:stripe-id stripe-customer-id})
           {:success true}
           (catch Throwable e
             {:error {:message "Exception in create-user-stripe-customer!"
                      :exception e}}))
      {:error {:message (str "No customer id returned by stripe.com for email: "
                             email " and uuid: " user-uuid)}})))

;; for testing purposes
(defn delete-user-stripe-customer! [{:keys [stripe-id user-id] :as _user}]
  (stripe/delete-customer! {:stripe-id stripe-id
                            :user-id user-id})
  (with-transaction
    (q/modify :web-user {:user-id user-id} {:stripe-id nil})))

(defn update-member-access-time [user-id project-id]
  (q/modify :project-member {:user-id user-id :project-id project-id}
            {:access-date (sql-now)}))

(defn create-user-stripe [stripe-acct user-id]
  (q/create :user-stripe {:stripe-acct stripe-acct :user-id user-id}))

(defn user-stripe-account [user-id]
  (q/find-one :user-stripe {:user-id user-id}))

(defn verified-primary-email?
  "Is this email already primary and verified?"
  [email]
  (pos? (count (q/find :user-email {:email email :principal true :verified true}))))

(defn create-email-verification! [user-id email & {:keys [principal] :or {principal false}}]
  (q/create :user-email {:user-id user-id
                         :email email
                         :verify-code (crypto.random/hex 16)
                         :principal principal}))

(defn user-email-status [user-id verify-code]
  (q/find-one :user-email {:user-id user-id :verify-code verify-code}
              [:verify-code :verified :email]))

(defn current-email-entry [user-id email]
  (q/find-one :user-email {:user-id user-id :email email}))

(defn primary-email-verified? [user-id]
  (when-let [email (q/get-user user-id :email)]
    (boolean (q/find-one :user-email {:user-id user-id :email email :principal true}
                         :verified))))

(defn set-primary-email!
  "Given an email, set it as the primary email address for user-id. This
  assumes that the email address has been confirmed"
  [user-id email]
  (with-transaction
    ;; set principal to false on all emails for user
    (q/modify :user-email {:user-id user-id} {:principal false :updated (sql-now)})
    ;; set principal to true for this email
    (q/modify :user-email {:user-id user-id :email email} {:principal true})
    ;; update the user's email status
    (q/modify :web-user {:user-id user-id} {:verified true :email email})))

(defn verify-email! [email verify-code user-id]
  (q/modify :user-email (cond-> {:email email :user-id user-id}
                          verify-code (assoc :verify-code verify-code))
            {:verified true :updated (sql-now)}))

(defn get-user-emails [user-id]
  (q/find :user-email {:user-id user-id :enabled true}))

(defn set-user-email-enabled! [user-id email enabled]
  (q/modify :user-email {:email email :user-id user-id} {:enabled enabled}))

(defn email-verify-code [user-id email]
  (q/find-one :user-email {:user-id user-id :email email} :verify-code))

(defn projects-labeled-summary
  "Return the count of articles and labels done by user-id grouped by projects"
  [user-id]
  (q/find [:article-label :al] {:al.user-id user-id :a.enabled true}
          [:a.project-id
           [:%count.%distinct.al.article-id :articles]
           [:%count.al.article-label-id :labels]]
          :join [[:article :a] :al.article-id], :group :a.project-id))

(defn projects-annotated-summary
  "Return the count of annotations done by user-id grouped by projects"
  [user-id]
  (q/find [:annotation :an] {:au.user-id user-id}
          [:a.project-id [:%count.an.annotation-id :annotations]]
          :join [[[:article :a]   :an.article-id]
                 [[:ann-user :au] :an.annotation-id]], :group :a.project-id))

(defn update-user-introduction! [user-id introduction]
  (q/modify :web-user {:user-id user-id} {:introduction introduction}))

(defn search-users
  "Return users whose email matches q"
  [q & {:keys [limit]
        :or {limit 5}}]
  (with-transaction
    (let [user-ids (->> ["SELECT user_id FROM web_user WHERE (email ilike ?) ORDER BY email LIMIT ?"
                         (str q "%")
                         limit]
                        db/raw-query
                        (map :user-id))]
      ;; check to see if we have results before returning the public info
      (if (empty? user-ids)
        user-ids
        (get-users-public-info user-ids)))))

(defn user-id-from-url-id [url-id]
  ;; TODO: implement url-id strings for users
  (util/parse-integer url-id))

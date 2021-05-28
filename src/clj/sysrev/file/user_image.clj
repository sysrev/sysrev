(ns sysrev.file.user-image
  (:require [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [gravatar.core :as gr]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.file.core :as file]))

;;;
;;; user-profile-image
;;;

(defn- profile-image-associated? [s3-id user-id]
  (q/find-one :user-profile-image {:s3-id s3-id :user-id user-id} :s3-id))

(defn- associate-profile-image [s3-id user-id]
  (db/with-transaction
    (when-not (profile-image-associated? s3-id user-id)
      (q/create :user-profile-image {:s3-id s3-id :user-id user-id :enabled true}))))

(defn- activate-profile-image
  "Activate s3-id for user-id, deactivate all others"
  [s3-id user-id]
  (db/with-transaction
    (q/modify :user-profile-image {:user-id user-id} {:enabled false})
    (q/modify :user-profile-image {:user-id user-id :s3-id s3-id} {:enabled true})))

(defn user-active-profile-image [user-id]
  (q/find-one [:user-profile-image :upi] {:user-id user-id :enabled true}
              [:s3.s3-id :s3.key :s3.filename :user-id :enabled :meta]
              :join [[:s3store :s3] :upi.s3-id]))

(defn- set-profile-image-meta [s3-id meta]
  (q/modify :user-profile-image {:s3-id s3-id} {:meta (db/to-jsonb meta)}))

(defn save-user-profile-image
  [user-id file filename & {:keys [activate] :or {activate true}}]
  (db/with-transaction
    (let [{:keys [key s3-id]} (file/save-s3-file :image filename {:file file})]
      (associate-profile-image s3-id user-id)
      (when activate (activate-profile-image s3-id user-id))
      {:user-id user-id :filename filename :s3-id s3-id :key key})))

;;;
;;; user-avatar-image
;;;

(defn user-active-avatar-image [user-id]
  (q/find-one [:user-avatar-image :ua] {:ua.user-id user-id :ua.enabled true}
              [:ua.user-id :ua.enabled :s3.s3-id :s3.key :s3.filename]
              :join [[:s3store :s3] :ua.s3-id]))

(defn- associate-user-avatar-image
  "Associate a s3store id with user's avatar image"
  [s3-id user-id]
  (q/create :user-avatar-image {:user-id user-id :s3-id s3-id}))

(defn save-user-avatar-image
  [user-id file filename & [meta]]
  (db/with-transaction
    ;; delete current entry
    (when-let [current (user-active-avatar-image user-id)]
      ;; can't delete this safely, file could be shared
      #_ (do (s3-file/delete-file (:key current) :image)
             (file/delete-s3-id (:s3-id current)))
      (q/delete :user-avatar-image {:user-id user-id :s3-id (:s3-id current)}))
    ;; save new file/entry
    (let [{:keys [key s3-id]} (file/save-s3-file :image filename {:file file})]
      (associate-user-avatar-image s3-id user-id)
      ;; change the coords on active profile img
      (when meta (some-> (:s3-id (user-active-profile-image user-id))
                         (set-profile-image-meta meta)))
      {:user-id user-id :filename filename :s3-id s3-id :key key})))

(defn delete-user-avatar-image [user-id]
  (db/with-transaction
    (when-let [{:keys [s3-id]} (user-active-avatar-image user-id)]
      (file/delete-s3-file :image s3-id)
      true)))

;;;
;;; gravatar
;;;

;; needed to prevent 403 (permission denied) from gravatar
(def fake-user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/33.0.1750.152 Safari/537.36")

(defn gravatar-image-data [email]
  (let [gravatar-url (gr/avatar-url email :default 404 :https true)
        response (try
                   (http/get gravatar-url {:connection-timeout 1000
                                           :socket-timeout 3000
                                           :throw-exceptions false
                                           :as :byte-array
                                           :headers {"User-Agent" fake-user-agent}})
                   (catch java.io.IOException e
                     ;; Catch network errors like UnknownHostException
                     (log/warn "Exception getting gravatar:" (class e))))]
    (when-not (= (:status response) 404)
      (:body response))))

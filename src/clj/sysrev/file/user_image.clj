(ns sysrev.file.user-image
  (:require [clj-http.client :as http]
            [gravatar.core :as gr]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.file.core :as file]))

;; TODO: is this unique?
(defn get-profile-image-s3-association [s3-id user-id]
  (q/find-one :user-profile-image {:s3-id s3-id :user-id user-id} :s3-id))

(defn associate-profile-image-s3-with-user [s3-id user-id]
  (q/create :user-profile-image {:s3-id s3-id :user-id user-id :enabled true}))

(defn activate-profile-image
  "Activate s3-id for user-id, deactivate all others"
  [s3-id user-id]
  (db/with-transaction
    (q/modify :user-profile-image {:user-id user-id} {:enabled false})
    (q/modify :user-profile-image {:user-id user-id :s3-id s3-id} {:enabled true})))

(defn active-profile-image-key-filename
  "Return the key, filename associated with the active profile"
  [user-id]
  (db/with-transaction
    (when-let [s3-id (q/find-one :user-profile-image {:user-id user-id :enabled true} :s3-id)]
      (q/find-one :s3store {:s3-id s3-id} [:s3-id :key :filename]))))

(defn active-profile-image-meta
  "Return image profile meta for user-id"
  [user-id]
  (q/find-one :user-profile-image {:user-id user-id :enabled true} :meta))

(defn update-profile-image-meta! [s3-id meta]
  (q/modify :user-profile-image {:s3-id s3-id} {:meta (db/to-jsonb meta)}))

(defn read-avatar [user-id]
  (q/find-one :user-avatar-image {:user-id user-id}))

(defn associate-avatar-image-with-user
  "Associate a s3store id with user's avatar image"
  [s3-id user-id]
  (q/create :user-avatar-image {:user-id user-id :s3-id s3-id}))

(defn avatar-image-key-filename
  "Return the key, filename associated with the active profile"
  [user-id]
  (when-let [s3-id (q/find-one :user-avatar-image {:user-id user-id :enabled true} :s3-id)]
    (q/find-one :s3store {:s3-id s3-id} [:s3-id :key :filename])))

;; needed to prevent 403 (permission denied) from gravatar
(def fake-user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/33.0.1750.152 Safari/537.36")

(defn gravatar-image-data [email]
  (let [gravatar-url (gr/avatar-url email :default 404 :https true)
        response (http/get gravatar-url {:throw-exceptions false
                                         :as :byte-array
                                         :headers {"User-Agent" fake-user-agent}})]
    (when-not (= (:status response) 404)
      (:body response))))

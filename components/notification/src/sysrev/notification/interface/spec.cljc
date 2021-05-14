(ns sysrev.notification.interface.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [sysrev.shared.spec.core :as sc]))

(def non-blank #(and (string? %) (not (str/blank? %))))

(def types
  [:article-reviewed :article-reviewed-combined
   :notify-user
   :group-has-new-project
   :project-has-new-article :project-has-new-article-combined
   :project-has-new-user :project-has-new-user-combined
   :project-invitation
   :system])

(s/def ::adding-user-id ::sc/user-id)
(s/def ::adding-user-name non-blank)
(s/def ::article-count pos-int?)
(s/def ::article-data-title non-blank)
(s/def ::description non-blank)
(s/def ::group-name non-blank)
(s/def ::image-uri non-blank)
(s/def ::inviter-id ::sc/user-id)
(s/def ::inviter-name non-blank)
(s/def ::new-user-id ::sc/user-id)
(s/def ::new-user-name non-blank)
(s/def ::new-user-names (s/coll-of ::new-user-name))
(s/def ::project-name non-blank)
(s/def ::type (s/and keyword? (set types)))
(s/def ::text non-blank)
(s/def ::uri non-blank)

(s/def ::content
  (s/keys
   :req-un [::type]
   :opt-un
   [::adding-user-name ::sc/article-id ::article-count ::article-data-title
    ::group-name ::image-uri ::inviter-name ::new-user-id ::new-user-name
    ::new-user-names ::sc/project-id ::project-name ::text ::uri ::sc/user-id]))

(s/def ::created inst?)
(s/def ::notification-id ::sc/sql-serial-id)
(s/def ::publisher-id ::sc/sql-serial-id)
(s/def ::topic-id ::sc/sql-serial-id)

(s/def ::notification
  (s/keys
   :req-un [::content ::created ::notification-id ::publisher-id ::topic-id]))

(s/def ::create-article-reviewed-notification-request
  (s/and
   #(= :article-reviewed (:type %))
   (s/keys :req-un [::sc/article-id ::article-data-title ::sc/project-id
                    ::project-name ::sc/user-id])))

(s/def ::create-group-has-new-project-notification-request
  (s/and
   #(= :group-has-new-project (:type %))
   (s/keys :req-un [::adding-user-id ::sc/group-id ::group-name ::sc/project-id
                    ::project-name])))

(s/def ::create-notify-user-notification-request
  (s/and
   #(= :notify-user (:type %))
   (s/keys :req-un [::text])))

(s/def ::create-project-has-new-article-notification-request
  (s/and
   #(= :project-has-new-article (:type %))
   (s/keys :req-un [::sc/article-id ::article-data-title
                    ::sc/project-id ::project-name]
           :opt-un [::adding-user-id ::adding-user-name])))

(s/def ::create-project-has-new-user-notification-request
  (s/and
   #(= :project-has-new-user (:type %))
   (s/keys :req-un [::new-user-id ::new-user-name ::sc/project-id ::project-name])))

(s/def ::create-project-invitation-notification-request
  (s/and
   #(= :project-invitation (:type %))
   (s/keys :req-un [::description ::sc/invitation-id ::inviter-id ::inviter-name
                    ::sc/project-id ::project-name ::sc/user-id])))

(s/def ::create-system-notification-request
  (s/and
   #(= :system (:type %))
   (s/keys :req-un [::text] :opt-un [::uri])))

(s/def ::create-notification-request
  (s/and
   (s/keys :req-un [::type] :opt-un [::image-uri])
   (s/or :article-reviewed ::create-article-reviewed-notification-request
         :group-has-new-project ::create-group-has-new-project-notification-request
         :notify-user ::create-notify-user-notification-request
         :project-has-new-article ::create-project-has-new-article-notification-request
         :project-has-new-user ::create-project-has-new-user-notification-request
         :project-invitation ::create-project-invitation-notification-request
         :system ::create-system-notification-request)))

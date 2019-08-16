(ns sysrev.file.core
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [orchestra.core :refer [defn-spec]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]))

(s/def ::time any?)
(s/def ::count int?)

(s/def ::s3-id int?)
(s/def ::key string?)
(s/def ::file-key ::key)
(s/def ::filename string?)
(s/def ::created ::time)

(defn-spec create-s3store ::s3-id
  [{:keys [key filename created] :as fields}
   (s/keys :req-un [::filename ::key] :opt-un [::created])]
  (db/with-transaction
    (if-let [s3-id (q/find-one :s3store {:key key :filename filename} :s3-id)]
      (do (log/warn "create-s3store - already have entry for (key, filename)")
          s3-id)
      (q/create :s3store fields, :returning :s3-id))))

(defn-spec s3-key-exists? boolean?
  [key (s/nilable string?)]
  (when key (boolean (q/find :s3store {:key key} :key))))

(defn-spec lookup-s3-id (s/nilable ::s3-id)
  [filename string?, key string?]
  (q/find-one :s3store {:filename filename :key key} :s3-id))

(defn-spec s3-key (s/nilable ::key)
  [s3-id (s/nilable int?)]
  (when s3-id (q/find-one :s3store {:s3-id s3-id} :key)))

(defn-spec delete-s3-id ::count
  [s3-id int?]
  (q/delete :s3store {:s3-id s3-id}))

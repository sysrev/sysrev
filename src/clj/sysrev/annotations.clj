(ns sysrev.annotations
  (:require [medley.core :as medley]
            [sysrev.db.queries :as q]
            [clojure.tools.logging :as log]
            [sysrev.util :as util]))

(defn find-annotation [match-by fields & {:keys [] :as opts}]
  (vec (util/apply-keyargs
        q/find [:annotation :ann] match-by fields
        (merge opts {:left-join (concat [[[:semantic-class :ann-sc] :ann.semantic-class-id]
                                         [[:ann-user :ann-u]        :ann.annotation-id]
                                         [[:ann-s3store :ann-s3]    :ann.annotation-id]
                                         [[:s3store :s3]            :ann-s3.s3-id]
                                         [[:article :a]             :ann.article-id]
                                         [[:article-data :ad]       :a.article-data-id]]
                                        (:left-join opts))}))))

(defn project-annotations-basic
  "Retrieve all annotations for project-id"
  [project-id]
  (find-annotation {:a.project-id project-id}
                   [:ann.selection :ann.annotation :ann.context :ann-sc.definition :ann-u.user-id
                    :a.article-id :s3.filename [:s3.key :file-key]]))

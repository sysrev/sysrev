(ns sysrev-web.ajax
  (:require
   [ajax.core :refer [GET POST]]
   [sysrev-web.base :refer [state server-data]]
   [sysrev-web.util :refer [nav nav-scroll-top]]
   [sysrev-web.notify :refer [notify]]))

(defn integerify-map-keys
  "Server-side maps with integer keys will have the keys changed to keywords
   when converted to JSON. This converts any integer keywords back to integers,
  operating recursively through nested maps."
  [m]
  (if (not (map? m))
    m
    (->> m
         (mapv (fn [[k v]]
                 (let [k-int (js/parseInt (name k))
                       k-new (if (integer? k-int) k-int k)
                       ;; integerify sub-maps recursively
                       v-new (if (map? v)
                               (integerify-map-keys v)
                               v)]
                   [k-new v-new])))
         (apply concat)
         (apply hash-map))))

(defn ajax-get
  "Performs an AJAX GET call with proper JSON handling.
  Calls function `handler` on the response map."
  ([url content handler]
   (GET url
        :format :json
        :response-format :json
        :keywords? true
        :params content
        :handler #(handler (integerify-map-keys %))))
  ([url handler]
   (ajax-get url nil handler)))

(defn ajax-post
  "Performs an AJAX POST call with proper JSON handling.
  Calls function `handler` on the response map."
  ([url content handler]
   (POST url
         :format :json
         :response-format :json
         :keywords? true
         :params content
         :handler #(handler (integerify-map-keys %))))
  ([url handler]
   (ajax-post url nil handler)))

(defn pull-identity []
  (ajax-get
   "/api/auth/identity"
   #(swap! state assoc :identity (:identity %))))

(defn pull-criteria []
  (when (nil? (:criteria @server-data))
    (ajax-get
     "/api/criteria"
     #(swap! server-data assoc :criteria %))))

(defn pull-all-labels []
  (ajax-get
   "/api/all-labels"
   (fn [response]
     (swap! server-data assoc
            :labels (:labels response)
            :labeled-articles (:articles response))
     (swap! server-data update
            :articles #(merge % (:articles response))))))

(defn pull-ranking-page [num]
  (when (nil? (get-in @server-data [:ranking :pages num]))
    (ajax-get
     (str "/api/ranking/" num)
     (fn [response]
       (let [ranked-ids (->> response
                             (sort-by (comp :score second))
                             (mapv first))]
         (swap! server-data update
                :articles #(merge % response))
         (swap! server-data assoc-in
                [:ranking :pages num] ranked-ids))))))

(defn pull-project-users []
  (ajax-get
   "/api/project-users"
   #(swap! server-data assoc :users %)))

(defn pull-initial-data []
  (pull-identity)
  (when (nil? (:criteria @server-data)) (pull-criteria))
  (when (nil? (:labels @server-data)) (pull-all-labels))
  (when (nil? (:users @server-data)) (pull-project-users))
  (when (contains? (:page @state) :ranking)
    (let [page-num (-> @state :page :ranking :ranking-page)]
      (when page-num
        (pull-ranking-page page-num)))))

(defn post-login [email password]
  (ajax-post
   "/api/auth/login"
   {:email email :password password}
   (fn [_]
     (pull-identity)
     (nav-scroll-top "/"))))

(defn post-register [email password]
  (ajax-post
   "/api/auth/register"
   {:email email :password password}
   ;; if register succeeds, send login request
   (fn [_] (post-login email password))))

(defn post-logout []
  (ajax-post
   "/api/auth/logout"
   nil
   (fn [_]
     (swap! state dissoc :identity)
     (nav-scroll-top "/")
     (notify "Logged out."))))

(defn submit-tag [{:keys [article-id criteria-id value]}]
  (ajax-post
   "/api/tag"
   {:article-id article-id
    :criteria-id criteria-id
    :value value}
   (fn [_] (notify "Tag saved"))))

(defn pull-label-tasks
  ([interval handler after-score]
   (ajax-get
    (str "/api/label-task/" interval)
    (when-not (nil? after-score) {:greaterThanScore after-score})
    (fn [response]
      (let [result (:result response)]
        (notify (str "Fetched " (count result) " more articles"))
        (handler result)))))
  ([interval handler]
   (pull-label-tasks interval handler 0.0)))

#_
(defn send-tags
  "send the criteria responses for a given article.
  Takes an article-id and a map of criteria-ids to booleans.
  Any unset criteria-ids will be unset on the server."
  [article-id criteria-values]
  (let [data (->> criteria-values
                  (map (fn [[cid, value]]
                         {:articleId article-id
                          :criteriaId (js/parseInt (name cid))
                          :value value})))]
    (ajax-post "/api/tags"
               {:tags data}
               (fn [response]
                 (let [err (:err response)
                       res (:result response)]
                   (when-not (empty? err) (notify (str "Error: " err)))
                   (when-not (empty? res) (notify "Tags saved")))))))

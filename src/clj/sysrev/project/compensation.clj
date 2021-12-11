(ns sysrev.project.compensation
  (:require [clojure.spec.alpha :as s]
            [orchestra.core :refer [defn-spec]]
            [clojure.string :as str]
            [clj-time.coerce :as tc]
            [clj-time.local :as l]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [honeysql.helpers :as sqlh :refer [modifiers]]
            [sysrev.db.core :as db]
            [sysrev.db.queries :as q]
            [sysrev.project.funds :refer [transaction-source-descriptor]]
            [sysrev.util :as util :refer [index-by nilable-coll sum]]))

;; for clj-kondo
(declare create-project-compensation! read-project-compensations)

(s/def ::time any?)
(s/def ::cents int?)

(s/def ::compensation map?)
(s/def ::compensation-id int?)

(s/def ::item string?)
(s/def ::amount ::cents)
(s/def ::rate (s/keys :req-un [::item ::amount]))

(def admin-fee 0.20)

(defn-spec create-project-compensation! ::compensation-id
  [project-id int?, rate ::rate]
  (db/with-clear-project-cache project-id
    (let [compensation-id (q/create :compensation {:rate (db/to-jsonb rate)}
                                    :returning :compensation-id)]
      (q/create :compensation-project {:project-id project-id
                                       :compensation-id compensation-id})
      compensation-id)))

(defn-spec read-project-compensations (nilable-coll ::compensation)
  [project-id int?]
  (q/find [:compensation :c] {:project-id project-id}
          [:c.compensation-id :c.rate :c.created :cp.enabled]
          :join [[:compensation-project :cp] :c.compensation-id]))

(defn start-compensation-period-for-user!
  "Make an entry into compensation_user_period for `compensation-id` and
  `user-id`. The period_end value is nil and represents a currently
  enabled compensation period."
  [compensation-id user-id]
  (q/create :compensation-user-period {:compensation-id compensation-id :user-id user-id}))

(defn end-compensation-period-for-user!
  "Mark the period_end as now for `compensation-id` with `user-id`.
  period_end must not already have been set."
  [compensation-id user-id]
  (q/modify :compensation-user-period
            {:compensation-id compensation-id, :user-id user-id, :period-end nil}
            {:period-end :%now}))

(defn- project-users [project-id]
  (q/find [:project-member :pm] {:project-id project-id} [:pm.user-id :u.email]
          :join [[:web-user :u] :pm.user-id]))

(defn ^:unused start-compensation-period-for-all-users!
  "Begin the compensation period for compensation-id for all users in project-id"
  [project-id compensation-id]
  (mapv #(start-compensation-period-for-user! compensation-id (:user-id %))
        (project-users project-id)))

(defn end-compensation-period-for-all-users!
  "End the compensation period for compensation-id for all users in project-id"
  [project-id compensation-id]
  (mapv #(end-compensation-period-for-user! compensation-id (:user-id %))
        (project-users project-id)))

(defn set-project-compensation-enabled! [project-id compensation-id enabled]
  (q/modify :compensation-project {:project-id project-id :compensation-id compensation-id}
            {:enabled enabled}))

(defn get-default-project-compensation [project-id]
  (q/find-one :compensation-project-default {:project-id project-id}
              :compensation-id))

(defn set-default-project-compensation!
  "Set the default compensation for `project-id` to `compensation-id`,
  or set none if `compensation-id` is nil."
  [project-id compensation-id]
  (q/delete :compensation-project-default {:project-id project-id})
  (when compensation-id
    (q/create :compensation-project-default {:project-id project-id
                                             :compensation-id compensation-id})))

;; for now, this is just the article labeled count. Eventually, should
;; use the "item" field of the rate on the compensation
(defn- compensation-owed-for-articles-for-user
  "Returns a count of articles associated with a compensation-id for
  user-id. start-date and end-date are of the form YYYY-MM-dd
  e.g. 2018-09-14 (or 2018-9-14). start-date is until the begining of
  the day (12:00:00AM) and end-date is until the end of the
  day (11:59:59AM). The default start-date is 1970-01-01 and the
  default end-date is today"
  [user-id project-id compensation-id & [start-date end-date]]
  (let [start-date (or start-date "1970-01-01")
        end-date (or end-date (->> (l/local-now) (f/unparse (f/formatters :date))))
        start-date-time (->> start-date (f/parse (f/formatters :date)))
        end-date-time (->> end-date (f/parse (f/formatters :date)))
        compensation-periods (q/find :compensation-user-period
                                     {:compensation-id compensation-id :user-id user-id}
                                     [:period-begin :period-end])
        time-period-statement (->> compensation-periods
                                   (mapv #(vector :and
                                                  [:>= :al.added-time (:period-begin %)]
                                                  [:<= :al.added-time
                                                   (or (:period-end %) (db/sql-now))]))
                                   (cons :or)
                                   (into []))]
    ;; check that the there is really a compensation with a time period
    (if (> (count time-period-statement) 1)
      (q/find-one [:article-label :al] {:a.project-id project-id :al.user-id user-id}
                  [[:%count.%distinct.al.article-id :count]]
                  :left-join [[:article :a] :al.article-id]
                  :where [:and
                          [:>= :al.added-time (-> start-date-time tc/to-sql-time)]
                          [:< :al.added-time (-> end-date-time (t/plus (t/days 1)) tc/to-sql-time)]
                          [:!= :al.confirm-time nil]
                          time-period-statement])
      ;; there isn't any time period associated with this compensation for this user
      0)))

(defn project-compensation-for-user
  "Calculate the total owed to user-id by project-id. start-date and
  end-date are of the form YYYY-MM-dd (e.g. 2018-09-14 or 2018-9-14).
  start-date is until the begining of the day (12:00:00AM) and
  end-date is until the end of the day (11:59:59AM)."
  [project-id user-id & [start-date end-date]]
  (->> (read-project-compensations project-id)
       (mapv (fn [{:keys [compensation-id rate]}]
               {:user-id user-id
                :project-id project-id
                :compensation-id compensation-id
                :rate rate
                :articles (compensation-owed-for-articles-for-user
                           user-id project-id compensation-id start-date end-date)}))))

(defn project-compensation-for-users
  "Return the amount-owed to users of project-id over start-date and end-date"
  [project-id & [start-date end-date]]
  (let [project-users (project-users project-id)
        users-map (index-by :user-id project-users)]
    (->> project-users
         (map #(project-compensation-for-user project-id (:user-id %) start-date end-date))
         flatten
         (map #(assoc % :name (-> (:email (get users-map (:user-id %)))
                                  (str/split #"@")
                                  first))))))

(defn- project-user-total-paid [project-id user-id]
  (sum (q/find :project-fund {:project-id project-id
                              :user-id user-id
                              :transaction-source "PayPal/payout-batch-id"}
               :amount, :where [:< :amount 0])))

(defn- project-user-total-earned [project-id user-id]
  (sum (->> (project-compensation-for-user project-id user-id)
            (map #(* (:articles %) (get-in % [:rate :amount]))))))

(defn- compensation-owed-to-user-by-project [project-id user-id]
  (+ (project-user-total-earned project-id user-id)
     (project-user-total-paid project-id user-id)))

(defn- project-user-last-payment [project-id user-id]
  (first (q/find :project-fund {:project-id project-id :user-id user-id}
                 :created, :where [:< :amount 0] :order-by [:created :desc])))

(defn compensation-owed-by-project [project-id]
  (db/with-transaction
    (vec (for [{:keys [user-id]} (project-users project-id)]
           (let [compensation-owed (compensation-owed-to-user-by-project project-id user-id)
                 admin-fee (Math/round ^Double (* admin-fee compensation-owed))]
             {:user-id user-id
              :compensation-owed compensation-owed
              :admin-fee admin-fee
              :last-payment (project-user-last-payment project-id user-id)})))))

(defn ^:unused project-total-paid [project-id]
  (sum (q/find :project-fund {:project-id project-id}
               :amount, :where [:< :amount 0])))

(defn project-total-owed [project-id]
  (sum (map :compensation-owed (compensation-owed-by-project project-id))))

(defn user-compensation
  "Return the current compensation-id associated with `user-id` for
  `project-id`, or nil if there is none."
  [project-id user-id]
  (-> (q/find [:compensation-user-period :cup] {:cp.project-id project-id
                                                :cup.user-id user-id
                                                :cup.period-end nil}
              [:cup.compensation-id :cp.project-id]
              :join [[:compensation-project :cp] :cup.compensation-id]
              :where [:<> :cup.period-begin nil]
              :prepare #(modifiers % :distinct))
      first :compensation-id))

(defn project-users-current-compensation
  "Return a list of all users of a project and their current
  compensation-id for `project-id`."
  [project-id]
  (for [{:keys [user-id] :as user} (project-users project-id)]
    (assoc user :compensation-id (user-compensation project-id user-id))))

(defn project-total-admin-fees [project-id]
  (sum (map :admin-fee (compensation-owed-by-project project-id))))

(defn- projects-compensating-user
  "Returns a list of projects with compensations for user."
  [user-id]
  (q/find [:compensation-user-period :cup] {:cup.user-id user-id}
          [:cp.project-id [:p.name :project-name]]
          :join [[[:compensation-project :cp] :cup.compensation-id]
                 [[:project :p]               :cp.project-id]]
          :prepare #(modifiers % :distinct)))

(defn payments-owed-to-user [user-id]
  (->> (projects-compensating-user user-id)
       (map (fn [{:keys [project-id] :as entry}]
              (assoc entry :total-owed (compensation-owed-to-user-by-project
                                        project-id user-id))))
       (filter #(pos? (:total-owed %)))))

(defn payments-paid-to-user [user-id]
  (q/find [:project-fund :pf] {:user-id user-id
                               :transaction-source (:paypal-payout transaction-source-descriptor)}
          [:pf.project-id [:p.name :project-name] [:pf.amount :total-paid] :pf.created]
          :join [[:project :p] :pf.project-id] :where [:< :pf.amount 0]))

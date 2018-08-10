(ns sysrev.views.list-pager
  (:require [re-frame.core :refer
             [subscribe dispatch dispatch-sync reg-sub reg-event-fx trim-v]]
            [sysrev.util :as util]
            [sysrev.shared.util :as sutil]))

(defn- state-path [instance-key & path]
  (vec (concat [:list-pager instance-key] path)))

(reg-sub
 ::page-input
 (fn [[_ panel ikey]]
   [(subscribe [:panel-field (state-path ikey :page-input) panel])])
 (fn [[page-input]] page-input))

(reg-event-fx
 ::set-page-input
 [trim-v]
 (fn [_ [panel ikey value]]
   {:dispatch
    [:set-panel-field (state-path ikey :page-input) value panel]}))

(defn- max-display-offset [{:keys [total-count
                                   items-per-page]}]
  (* items-per-page
     (quot (dec total-count) items-per-page)))

(defn- ListPagerMessage [{:keys [offset
                                 total-count
                                 items-per-page
                                 item-name-string]
                          :as config}]
  (let [end-offset (min (dec total-count)
                        (+ offset items-per-page))]
    [:h5.list-pager-message
     (if (or (nil? total-count) (zero? total-count))
       (if (util/full-size?)
         (str "No matching " item-name-string " found")
         (str "No results found"))
       (if (util/full-size?)
         (str "Showing "
              (inc offset) " to " (inc end-offset)
              " of " total-count " matching " item-name-string)
         (str "Showing "
              (inc offset) "-" (inc end-offset)
              " of " total-count)))]))

(defn- ListPagerNav [{:keys [panel
                             instance-key
                             list-name
                             offset
                             total-count
                             items-per-page
                             item-name-string
                             set-offset
                             on-nav-action
                             recent-nav-action
                             loading?]
                      :as config}]
  (let [full-size? (util/full-size?)
        max-offset (max-display-offset config)
        max-page (inc (quot max-offset items-per-page))
        current-page-display
        (or @(subscribe [::page-input panel instance-key])
            (inc (quot offset items-per-page)))
        set-page-input #(dispatch-sync [::set-page-input panel instance-key %])
        on-nav (fn [action offset]
                 (set-offset offset)
                 (when on-nav-action (on-nav-action action offset))
                 (set-page-input nil))
        have-previous? (> offset 0)
        have-next? (< (+ offset items-per-page) total-count)
        on-first #(when have-previous?
                    (on-nav :first 0))
        on-last #(when have-next?
                   (on-nav :last max-offset))
        on-previous #(when have-previous?
                       (on-nav :previous (max 0 (- offset items-per-page))))
        on-next #(when have-next?
                   (on-nav :next (+ offset items-per-page)))
        on-page-num
        (util/wrap-prevent-default
         (fn []
           (let [value (sutil/parse-integer current-page-display)]
             (if (and (integer? value)
                      (< 0 value
                         (inc (js/Math.ceil (/ total-count items-per-page)))))
               (on-nav :page (* (dec value) items-per-page))
               (set-page-input nil)))))
        nav-loading? #(and loading? (= % recent-nav-action))
        nav-class
        (fn [action]
          (let [enabled?
                (cond (sutil/in? [:first :previous] action) have-previous?
                      (sutil/in? [:next :last] action)      have-next?
                      :else                                 nil)]
            (str (if enabled? "" "disabled ")
                 (if (nav-loading? action) "loading" ""))))]
    [:div.list-pager-nav
     (when full-size?
       [:span.page-number
        "Page "
        [:form.ui.small.input.page-number
         {:on-submit on-page-num}
         [:input.page-number
          {:type "text"
           :value (str current-page-display)
           :on-change #(set-page-input (-> % .-target .-value))
           :on-focus #(set-page-input "")
           :on-blur #(set-page-input nil)}]]
        " of " (str max-page)])
     [:div.ui.tiny.icon.button.nav-first
      {:class (nav-class :first)
       :on-click on-first}
      [:i.angle.double.left.icon]]
     [:div.ui.tiny.buttons.nav-prev-next
      [:div.ui.button
       {:class (str (nav-class :previous)
                    (if full-size? "" " icon"))
        :on-click on-previous}
       [:i.chevron.left.icon] (when full-size? "Previous")]
      [:div.ui.button
       {:class (str (nav-class :next)
                    (if full-size? "" " icon"))
        :on-click on-next}
       (when full-size? "Next") [:i.chevron.right.icon]]]
     [:div.ui.tiny.icon.button.nav-last
      {:class (nav-class :last)
       :on-click on-last}
      [:i.angle.double.right.icon]]]))

(defn ListPager
  "Reagent component for a navigation pager for multi-page item list.

  panel :
    Panel (vector of keywords) location for UI state
  instance-key :
    A unique value to reference this instance within panel state
  offset :
    Current offset (within full list) of first item on page
  total-count :
    Number of items in full list
  items-per-page :
    Max number of items displayed per page
  item-name-string :
    Pluralized name for item (to show in messages)
  set-offset :
    1-arg function to change current offset
  on-nav-action :
    (Optional) 2-arg function hook called when navigation button activated
    First arg is action keyword [:first :previous :next :last]
    Second arg is new offset
  recent-nav-action :
    (Optional) Action keyword value of recently activated on-nav-action hook
    Used to render loading status on individual button
  loading? :
    (Optional) Boolean indicating whether list data is currently loading
  message-overrides :
    (Optional) Top-level config map is merged with this for rendering
    status message component
  props :
    (Optional) Hiccup props map for top-level component"
  [{:keys [panel
           instance-key
           offset
           total-count
           items-per-page
           item-name-string
           set-offset
           on-nav-action
           recent-nav-action
           loading?
           message-overrides
           props]
    :as config}]
  (let [message-config (merge config message-overrides)]
    (if (util/full-size?)
      [:div.ui.two.column.middle.aligned.grid.list-pager
       (merge {} props)
       [:div.left.aligned.column
        [ListPagerMessage message-config]]
       [:div.right.aligned.column
        [ListPagerNav config]]]
      [:div.ui.middle.aligned.grid.list-pager
       (merge {} props)
       [:div.left.aligned.eight.wide.column
        [ListPagerMessage message-config]]
       [:div.right.aligned.eight.wide.column
        [ListPagerNav config]]])))

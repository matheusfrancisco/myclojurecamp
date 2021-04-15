(ns dojo.jobs.match-email
  (:require
    [clojure.string :as string]
    [chime.core :as chime]
    [pairing-scheduler.core :as ps]
    [dojo.email :as email]
    [dojo.db :as db])
  (:import
    (java.time Period DayOfWeek ZonedDateTime ZoneId LocalTime)
    (java.time.format DateTimeFormatter)
    (java.time.temporal TemporalAdjusters)))

(def ->java-day-of-week
  {:monday DayOfWeek/MONDAY
   :tuesday DayOfWeek/TUESDAY
   :wednesday DayOfWeek/WEDNESDAY
   :thursday DayOfWeek/THURSDAY
   :friday DayOfWeek/FRIDAY
   :saturday DayOfWeek/SATURDAY
   :sunday DayOfWeek/SUNDAY})

(defn generate-schedule!
  "Returns a list of maps, with :guest-ids, :day-of-week and :Time-of-day,
    ex.
    [{:guest-ids #{123 456}
      :day-of-week :monday
      :time-of-day 1200} ...]"
  []
  (let [users (db/get-users)]
    (->> {:max-events-per-day (->> users
                                   (map (fn [user]
                                         [(:user/id user) 1]))
                                   (into {}))
          :availabilities (->> users
                               (map (fn [user]
                                     [(:user/id user)
                                      ;; stored as {[:monday 10] :available
                                      ;;            [:tuesday 10] :preferred
                                      ;;            [:wednesday 10] nil)
                                      ;; but need #{[:monday 10 :available]
                                      ;;            [:tuesday 10 :preferred]}
                                      ;; also, remove when value is nil
                                      (->> (:user/availability user)
                                           (filter (fn [[k v]] v))
                                           (map (fn [[k v]]
                                                  (conj k v)))
                                           set)]))
                               (into {}))}
         (ps/generate-initial-schedule 1)
         ps/optimize-schedule
         :schedule)))

(defn group-by-guests
  [schedule]
  (reduce (fn [memo event]
           (-> memo
               (update (first (:guest-ids event)) (fnil conj #{}) event)
               (update (last (:guest-ids event)) (fnil conj #{}) event)))
     {} schedule))

(defn sunday-email-template
  [user-id events]
  (let [get-user (memoize db/get-user)
        user (db/get-user user-id)
        events (->> events
                    (map (fn [event]
                           (assoc event :date
                             (.adjustInto
                               (LocalTime/of (:time-of-day event) 0)
                               (.with (ZonedDateTime/now (ZoneId/of "America/Toronto"))
                                 (TemporalAdjusters/next (->java-day-of-week (:day-of-week event)))))))))]
   {:to (:user/email user)
    :subject "ClojoDojo - Your Matches for this Week"
    :body [:div
           [:p "Hi " (:user/name user) ","]
           [:p "Here are your pairing sessions for next week:"]
           (for [event (sort-by :date events)
                 :let [partner (get-user (first (disj (:guest-ids event) user-id)))]]
            [:div.event
             [:span (.format (:date event)
                     (DateTimeFormatter/ofPattern "eee MMM dd 'at' HH:mm"))]
             " with "
             [:span.guest
              (:user/name partner)
              " (" (:user/email partner) ")"]])
           [:p "If you can't make a session, be sure to let your partner know!"]]}))

#_(let [[user-id events] (first (group-by-guests (generate-schedule!)))]
   (email/send! (sunday-email-template user-id events)))

(defn send-sunday-emails! []
  (let [user-id->events (->> (generate-schedule!)
                             group-by-guests)]
   (doseq [[user-id events] user-id->events]
     (email/send! (sunday-email-template user-id events)))))

(defn schedule-email-job! []
  (chime/chime-at
    (->> (chime/periodic-seq
           (.adjustInto (LocalTime/of 18 0)
                        (ZonedDateTime/now (ZoneId/of "America/Toronto")))
           (Period/ofDays 1))
         (filter (fn [instant]
                   (= DayOfWeek/SUNDAY (.getDayOfWeek instant)))))
    (fn [_]
     (send-sunday-emails!))))

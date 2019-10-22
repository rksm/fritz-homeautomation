(ns fritz-homeautomation.db-access
  (:require [fritz-homeautomation.fritz-api :refer [fetch-device-list fetch-stats-of-all-devices get-sid]]
            [java-time :as t]
            [fritz-homeautomation.db :as db]
            [com.rpl.specter :as s :refer [transform ALL MAP-VALS MAP-KEYS]]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as string]
            [oz.core :as oz]
            [clojure.java.io :as io])
  (:import java.sql.Timestamp
           org.joda.time.Instant
           org.joda.time.Duration))


(defn no-underscore [string]
  (string/replace string #"_" "-"))

(defn query
  ([table]
   (query table nil nil))
  ([table start-time]
   (query table start-time nil))
  ([table start-time end-time]
   (let [start (when start-time (format "time >= timestamp '%s'" start-time))
         end (when end-time (format "time <= timestamp '%s'" end-time))
         where (some->> [start end] (keep not-empty) seq (string/join " AND "))
         query (str "select * from " (name table))
         query (if where (str query " where " where) query)
         query (str query " order by device_id, time")]
     query)))

(defn query! [start-time end-time]
  (let [watts (->>
               (db/with-connection (db/execute! [(query "watts" start-time end-time)]))
               (transform [ALL MAP-KEYS] #(-> % name no-underscore keyword)))
        watts-by-device (group-by :device-id watts)
        temps (->>
               (db/with-connection (db/execute! [(query "temperature" start-time end-time)]))
               (transform [ALL MAP-KEYS] #(-> % name no-underscore keyword)))
        temps-by-device (group-by :device-id temps)]
    {:temps temps-by-device
     :watts watts-by-device}))


(def month-start (-> (t/local-date) (t/adjust :first-day-of-month) (.atStartOfDay)))
(def month-end (-> (t/local-date) (t/adjust :last-day-of-month) (.atTime 23 59)))

(def week-start (-> (t/local-date)
                    (t/property :day-of-week)
                    (t/with-value 1)
                    (.atStartOfDay)))
(def week-end (-> (t/local-date)
                  (t/property :day-of-week)
                  (t/with-value 7)
                  (.atTime (t/local-time 23 59))))

(def day-start (-> (t/local-date) .atStartOfDay))
(def day-end (-> (t/local-date) (.atTime (t/local-time 23 59))))

(defn query-some! []
  {:today (query! day-start nil)
   :yesterday (query! (-> day-start (t/minus (t/days 1))) (-> day-end (t/minus (t/days 1))))
   :this-week (query! week-start week-end)
   :this-month (query! month-start month-end)})

(comment

  (def x (query-some!))

  (require 'clojure.contrib.humanize)
  (clojure.contrib.humanize/filesize (count (pr-str x)))

(require 'java-time.repl)
 (java-time.repl/show-adjusters)
 (java-time.repl/show-fields)

 (-> (t/local-date)
     (t/property :day-of-week)
     (t/with-value 1))

 (def month-start (-> (t/local-date) (t/adjust :first-day-of-month) (.atStartOfDay)))
 (def month-end (-> (t/local-date) (t/adjust :last-day-of-month) (.atTime 23 59)))

 (def week-start (-> (t/local-date)
                     (t/property :day-of-week)
                     (t/with-value 1)
                     (.atStartOfDay)))
 (def week-end (-> (t/local-date)
                   (t/property :day-of-week)
                   (t/with-value 7)
                   (.atTime (t/local-time 23 59))))

 (def day-start (-> (t/local-date) .atStartOfDay))
 (def day-end (-> (t/local-date) (.atTime (t/local-time 23 59))))


 (query! day-start nil)
 (query! (-> day-start (t/minus (t/days 1))) (-> day-end (t/minus (t/days 1))))
 (query! week-start week-end)
 (query! month-start month-end)


 )

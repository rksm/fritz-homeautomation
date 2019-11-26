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


(defn- no-underscore [string]
  (string/replace string #"_" "-"))

(defn- query
  ([table attr time-group]
   (query table attr time-group nil nil))
  ([table attr time-group start-time]
   (query table attr time-group start-time nil))
  ([table attr time-group start-time end-time]
   (str "
SELECT device_id, " attr ", time_group AS time
FROM (SELECT device_id AS device_id, avg(" attr ") AS " attr ", date_trunc('" time-group "', time) AS time_group
      FROM " table "
      WHERE time >= timestamp '" start-time "'" (if end-time (str " AND time <= timestamp '" end-time "'") "") "
      GROUP BY device_id, time_group
      ORDER BY device_id, time_group) AS data;")))

(defn query! [start-time end-time]
  (let [watts (->>
               (db/with-connection (db/execute! [(query "watts" "watt" "hour" start-time end-time)]))
               (transform [ALL MAP-KEYS] #(-> % name no-underscore keyword)))
        watts-by-device (group-by :device-id watts)
        temps (->>
               (db/with-connection (db/execute! [(query "temperature" "temp" "hour" start-time end-time)]))
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

  (def x (query! month-start month-end))
  (query :temperature month-start month-end)

  "select * from temperature where time >= timestamp '2019-11-01T00:00' AND time <= timestamp '2019-11-30T23:59' order by device_id, time"
  (-> x :temps vals second count)

  (->>
   (db/with-connection (db/execute! [(query :temperature day-start)]))
   (transform [ALL MAP-KEYS] #(-> % name no-underscore keyword)))

  (let [
        table "temperature"
        attr "temp"
        ;; table "watts"
        ;; attr "watt"
        time-group "hour"
        start-time day-start
        end-time day-end] (->>
           (db/with-connection (db/execute! [(str "
SELECT device_id, " attr ", time_group AS time
FROM (SELECT device_id AS device_id, avg(" attr ") AS " attr ", date_trunc('" time-group "', time) AS time_group
      FROM " table "
      WHERE time >= timestamp '" start-time "' AND time <= timestamp '" end-time "'
      GROUP BY device_id, time_group
      ORDER BY device_id, time_group) AS data;")]))
           (transform [ALL MAP-KEYS] #(-> % name no-underscore keyword))
           clojure.pprint/pprint))

  (query! day-start day-end)

  (clojure.pprint/pprint (db/with-connection (db/execute! ["SELECT device_id as device_id, temp, to_timestamp(x, 'YYYYMMDD HH24') as time FROM (SELECT device_id as device_id, avg(temp) as temp, to_char(time, 'YYYYMMDD HH24') as x FROM temperature group by device_id, x order by x) as temp limit 5;"])))
  (clojure.pprint/pprint (db/with-connection (db/execute! ["SELECT count(*) FROM (SELECT avg(temp) as temp, to_char(time, 'YYYYMMDD HH24') as x FROM temperature group by x order by x) as temp;"])))
  (clojure.pprint/pprint (db/with-connection (db/execute! ["SELECT watt, to_timestamp(x, 'YYYYMMDD HH24') as time FROM (SELECT avg(watt) as watt, to_char(time, 'YYYYMMDD HH24') as x FROM watts group by x order by x) as watts;"])))
  (clojure.pprint/pprint (db/with-connection (db/execute! ["SELECT count(*) FROM (SELECT avg(watt) as watt, to_char(time, 'YYYYMMDD HH24') as x FROM watts group by x order by x) as watts;"])))

  (db/with-connection (db/execute! ["SELECT t
FROM   generate_series('2004-03-07'::timestamp
                     , '2004-03-08'::timestamp
                     ,  '10 minutes'::interval) AS t;"]))

  (def x (query-some!))

  (def one-minute (* 1000 60))
  (def one-hour (* 1000 60 60))

  (loop [data (-> x :this-month :temps (get "11630 0069103"))
         fixed-data []
         result []]
    (let [[a b & _] data]
      (if (or (not a) (not b))
        fixed-data
        (let [dt (- (.getTime (:time b)) (.getTime (:time a)))
              fixed-data (if (> dt one-hour)
                           (conj fixed-data
                                 a
                                 (assoc a
                                        :time (java.sql.Timestamp. (+ (.getTime (:time a)) one-minute))
                                        :temp 0)
                                 (assoc b
                                        :time (java.sql.Timestamp. (- (.getTime (:time b)) one-minute))
                                        :temp 0)
                                 b)
                           (conj fixed-data a b))]
          (recur (rest data)
                 (conj fixed-data)
                 (conj result dt))))))

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

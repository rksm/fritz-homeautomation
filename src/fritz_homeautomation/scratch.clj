(ns fritz-homeautomation.scratch
  (:require [fritz-homeautomation.fritz-api :refer [check-sid fetch-device-list fetch-stats-of-all-devices get-sid]]
            [clj-time.core :as t]
            [clj-time.format :as tf]
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

(def start-time (t/today-at 0 0))
(def end-time nil)
;; (def start-time (t/with-time-at-start-of-day (t/yesterday)))
;; (def end-time (t/today-at 0 1))
;; (def start-time nil)
;; (def end-time nil)

(def watts (->>
            (db/with-connection (db/execute! [(query "watts" start-time end-time)]))
            (transform [ALL MAP-KEYS] #(-> % name no-underscore keyword))))

(def watts-by-device (group-by :device-id watts))

(def temps (->>
            (db/with-connection (db/execute! [(query "temperature" start-time end-time)]))
            (transform [ALL MAP-KEYS] #(-> % name no-underscore keyword))))

(def temps-by-device (group-by :device-id temps))

;; (db/with-connection (db/execute! ["select count(*) from watts"]))

(defn device-viz [data what]
  {:width 3000
   :data {:values data}
   :mark "area"
   :encoding {:x {:field "time"
                  :type "temporal"}
              :y {:field what
                  :type "quantitative"
                  :scale {:zero false :nice true}}
              :tooltip [{:field "time" :type "nominal"}
                        {:field what :type "quantitative"}]}})


(comment
  (oz/start-server!))

(oz/view! [:div "..."
           (doall (for [[_ data] watts-by-device]
                    [:vega-lite (device-viz data "watt")]))
           (doall (for [[_ data] temps-by-device]
                    [:vega-lite (device-viz data "temp")]))])

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn time-intervals-with-no-values
  "If there is more then `threshold-ms` time between two subsequent values in
  data, generate a timespan (start / end instant) of the missing time."
  [data-by-device identifier threshold-ms]
  (let [d (get data-by-device identifier)]
    (for [[i a b] (map vector (range) d (rest d))
          :let [time-a (.getTime (:time a))
                time-b (.getTime (:time b))
                delta-ms (- time-b time-a)]
          :when (> delta-ms threshold-ms)]
      [time-a time-b])))

(defn times-between [start end interval-ms]
  (loop [now start times []]
    (let [next (.plus now (Duration/millis interval-ms))]
      (if (.isAfter next end)
        times
        (recur next (conj times next))))))

(defn generate-fill-values [identifier interval-ms time-intervals-with-no-values]
  (for [[time-a time-b] time-intervals-with-no-values
        :let [time-a (Instant/ofEpochMilli time-a)
              time-b (Instant/ofEpochMilli time-b)]
        time (times-between time-a time-b interval-ms)]
    [identifier 0 (Timestamp. (.getMillis time))]))

(comment

  (def three-minutes (* 3 60 1000))

 (def identifier "11630 0069103")

 (doseq [identifier (keys temps-by-device)]
   (let [times (time-intervals-with-no-values watts-by-device identifier three-minutes)
         fill (generate-fill-values identifier three-minutes times)]
     (db/with-connection (db/insert-watts! fill)))))

(ns fritz-homeautomation.scratch
  (:require [fritz-homeautomation.fritz-api :refer [check-sid fetch-device-list fetch-stats-of-all-devices get-sid]]
            [clj-time.core :as t]
            [clj-time.format :as tf]))

(do

  (def user (System/getenv "FRITZ_USER"))
  (def password (System/getenv "FRITZ_PASSWORD"))
  (def sid (get-sid user password))
  (check-sid sid)
  (def devices (fetch-device-list sid))
  (def device (first devices))
  (def all-stats (fetch-stats-of-all-devices sid))

  )


(def temps (-> all-stats first :stats :temperature-stats first))
(def watts (-> all-stats first :stats :power-stats first))

(uniq-values (data (t/now) temps))
(uniq-values (data (t/now) watts))

(defn uniq-values [data]
  (loop [[next & rest] data last-val nil result []]
    (if-not next
      result
      (let [val (:val next)]
        (recur rest
               val
               (if (= val last-val)
                 result
                 (conj result next)))))))

(defn time-seconds-ago [time-now secs]
  (tf/unparse
   (:mysql tf/formatters)
   (t/minus time-now (t/seconds secs))))

(t/to-time-zone (t/time-now))

(.withZone (t/now) (t/default-time-zone))

(time-seconds-ago (t/now) 20)
(time-seconds-ago (t/time-now) 20)

(t/to-time-zone
 (tf/parse (time-seconds-ago (t/now) 20))
 (t/default-time-zone))

(t/minus  (t/seconds 20))
(t/time-now)



(defn data [time-now {:keys [interval-seconds unit stat-values] :as stat}]
  (for [[i val] (map-indexed vector stat-values)]
    {:time (time-seconds-ago time-now (* i interval-seconds))
     :val val}))

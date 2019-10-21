(ns fritz-homeautomation.fritz-observer
  (:require [fritz-homeautomation.fritz-api :refer [check-sid fetch-device-list fetch-stats-of-all-devices get-sid]]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [chime :refer [chime-at]]
            [clj-time.periodic :as periodic]
            [com.stuartsierra.component :as component]
            [fritz-homeautomation.db :as db]
            [com.rpl.specter :as s :refer [transform MAP-VALS MAP-KEYS]]
            [clojure.set :refer [rename-keys]])
  (:import java.time.LocalDateTime
           java.time.Duration
           java.sql.Timestamp))


;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defrecord Timer
    [period do-fn stop-fn]
    component/Lifecycle
    (start [component]
      (println "starting timer")
      (let [period (periodic/periodic-seq (t/now) period)
            stop-fn (chime-at period do-fn)]
        (assoc component :stop-fn stop-fn)))
    (stop [component]
      (stop-fn)
      (assoc component :stop-fn nil)))

(defn make-timer [do-fn]
  (map->Timer {:period (t/seconds (* 60 5))
               :do-fn do-fn}))

(comment
  (def t (map->Timer {:period (t/seconds 1)
                      :do-fn (fn [time] (println "xxx" time))}))
 (def t (component/start t))
 (def t (.stop t)))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn time-seconds-ago [^LocalDateTime time-now ^long secs]
  (Timestamp/valueOf
   (.minus time-now (Duration/ofSeconds secs))))

(defn stat-values->vec [time-now device-id {:keys [interval-seconds unit stat-values] :as stat}]
  (for [[i val] (map-indexed vector stat-values)]
    [device-id (float val) (time-seconds-ago time-now (* i interval-seconds))]))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn uniq-values [data]
  (loop [[next & rest] data last-val nil result []]
    (if-not next
      result
      (let [[_ val _] next]
        (recur rest
               val
               (if (= val last-val)
                 result
                 (conj result next)))))))

(defn latest-by-device [table-key val-key]
  (let [val (name val-key)
        table (name table-key)
        q (format "
WITH newest AS (select device_id, max(time) AS time FROM %s GROUP BY device_id)
SELECT %s.device_id, %s.time, %s.%s
FROM %s, newest
WHERE %s.time = newest.time AND
      %s.device_id = newest.device_id" table table table table val table table table )]
    (let [rows (->> (db/execute! [q]))]
      (into {}
            (for [row rows
                  :let [{:keys [device_id] :as row} (transform [MAP-KEYS] #(-> % name keyword) row)]]
              [device_id (-> row
                             (dissoc :device_id)
                             (rename-keys (assoc {} val-key :val)))])))))

(defn stats-to-commit [all-stats time-now latest-watts latest-temps]
  (letfn [(filter-data [identifier data latest-data]
            (let [data (some->> data (stat-values->vec time-now identifier) uniq-values)]
              (if-not data
                nil
                (let [latest (get latest-data identifier)]
                  (if-not latest
                    data
                    (let [{:keys [time val]} latest
                          data (filter (fn [[_ _ time-ea]] (> (.getTime time-ea) (.getTime time))) data)
                          ;; remove duplicated first bit if we have enough data
                          data (if (and (> (count data) 1) (= val (-> data first second)))
                                 (rest data)
                                 data)]
                      data))))))]
    (into {}
          (for [{:keys [identifier] :as device} all-stats]
            [identifier
             ;; for temps and watts: transform both list so that they are in a
             ;; vector that can be passed to insert. Ex: [[ "11630 0069103" 22.0 2019-10-20 03:53:54.741693 ] ...]
             ;; also remove entries that are older than the entries in the db
             ;; if the newest entry in the db has the same value as the first
             ;; entry then drop the first
             {:device device
              :temps (filter-data identifier
                                  (some->> device :stats :temperature-stats first)
                                  latest-temps)
              :watts (filter-data identifier
                                  (some->> device :stats :power-stats first)
                                  latest-watts)}]))))

(comment
  (do (println "---------------------------") (fetch-and-commit! user password))
  )

(defn count-data []
  (first (db/execute! ["select
(select count(*) as count_watts from watts),
(select count(*) as count_temps from temperature)"])))


(defn commit-stats! [commit-data]
  (let [{:keys [count_watts count_temps]} (count-data)]
    (doseq [[_ {:keys [device watts temps]}] commit-data]
      (db/ensure-device! device)
      (when (seq temps) (db/insert-temps! temps))
      (when (seq watts) (db/insert-watts! watts)))
    (let [{count_watts_new :count_watts count_temps_new :count_temps} (count-data)]
      {:watts (- count_watts_new count_watts)
       :temps (- count_temps_new count_temps)})))

(defn fetch-and-commit!
  ([sid]
   (let [all-stats (fetch-stats-of-all-devices sid)
         now (LocalDateTime/now)
         commit-data (stats-to-commit all-stats now
                                      (latest-by-device :watts :watt)
                                      (latest-by-device :temperature :temp))]
     (commit-stats! commit-data)))
  ([user password]
   (fetch-and-commit! (get-sid user password))))

(comment
 (do
   (def user (System/getenv "FRITZ_USER"))
   (def password (System/getenv "FRITZ_PASSWORD"))
   (def user "admin")
   (def password "down8406")
   (def sid (get-sid user password))
   (def all-stats (fetch-stats-of-all-devices sid)))

 (do
   (def device (-> all-stats first))
   (def identifier (->> all-stats first :identifier))
   (def time-now (LocalDateTime/now))
   (db/with-connection
     (def latest-watts (latest-by-device :watts :watt))
     (def latest-temps (latest-by-device :temperature :temp))
     (def commit-data (stats-to-commit all-stats time-now (latest-by-device :watts :watt) (latest-by-device :temperature :temp))))
   )
 (db/with-connection (latest-by-device :watts :watt))

 (some->> all-stats first :stats :temperature-stats first (stat-values->vec time-now identifier))
 (some->> data )

 (fetch-and-commit! user password)


 (def watts (->> device :stats :power-stats first (data (LocalDateTime/now) id)))
 (count watts)
 (count (uniq-values watts))

 (db/execute! ["(select count(*) as watts from watts) UNION ALL (select count(*) as temps from temperature)"])
 (db/execute! ["select count(*) from watts"])
 (db/execute! ["select count(*) from temperature"])
 (db/execute! ["select * from temperature order by device_id, time"])
 (db/execute! ["select * from watts order by device_id, time"])

 (db/execute! ["delete from watts"])
 (db/execute! ["CREATE INDEX temp_device_time ON temperature (device_id, time);"])
 (db/execute! ["ALTER TABLE temperature ALTER COLUMN temp TYPE float(1);"])

 (into {}
       '(("11630 0069103" #inst "2019-10-19T21:50:50.000000000-00:00") ("11657 0272633" #inst "2019-10-19T21:50:50.000000000-00:00")))
 (into {}
       [["11630 0069103" #inst "2019-10-19T21:50:50.000000000-00:00"] ["11657 0272633" #inst "2019-10-19T21:50:50.000000000-00:00"]]))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce system-state (atom nil))

(defn stop! []
  (if-let [system @system-state]
    (try
      (component/stop system)
      (reset! system-state nil)
      (catch Exception e
        (println "error stopping old system:" e)))))

(defn start! []
  (db/migrate-fwd!)
  (stop!)
  (let [user (System/getenv "FRITZ_USER")
        password (System/getenv "FRITZ_PASSWORD")
        system (component/system-map
                :timer (make-timer (fn [time]
                                     (try
                                       (println "commiting new values " time)
                                       (let [new-vals (db/with-connection (fetch-and-commit! user password))]
                                         (println new-vals))
                                       (println "done")
                                       (catch Exception e
                                         (println "error commiting:" e))))))]
    (reset! system-state (component/start system))
    (println (db/with-connection (fetch-and-commit! user password)))))

(comment
  (start!)
  (stop!)


  (db/with-connection (fetch-and-commit! user password))

  (def system (component/start system))
  (def system (component/stop system))

  (db/execute! ["SELECT * from device"]))

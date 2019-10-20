(ns fritz-homeautomation.db
  (:require [next.jdbc :as jdbc]
            [taoensso.timbre :as timbre]
            [clojure.java.classpath :as cp]
            [clojure.pprint :refer [pprint]]
            [migratus.core :as migratus]
            [com.stuartsierra.component :as component]
            [next.jdbc.sql :as sql]))


(def db {:dbtype "postgresql"
         :dbname "fritz_data"
         :user (System/getenv "PGUSER")
         :password (System/getenv "PGPASSWORD")
         ;; :host "localhost"
         :host "192.168.178.30"
         :port 5432})

(def ^:dynamic *connection* nil)

(defmacro with-connection
  [& body]
  `(with-bindings {#'*connection* (.getConnection (jdbc/get-datasource ~db))}
     (assert (not (.isClosed *connection*)) "db connection could not be established")
     (try
       ~@body
       (finally
         (.close *connection*)))))

(defn execute! [query]
  (if-let [con *connection*]
    (jdbc/execute! con query)
    (throw (Exception. "no database connection"))))

(defn insert-multi! [table cols rows]
  (if-let [con *connection*]
    (sql/insert-multi! con table cols rows)
    (throw (Exception. "no database connection"))))

(defn ensure-device! [device]
  (let [{:keys [name identifier]} device]
    (execute! ["
INSERT INTO device (name, identifier)
VALUES (?, ?)
ON CONFLICT (identifier)
DO NOTHING " name identifier])))

(defn insert-watts! [watts]
  (insert-multi! :watts
                 [:device_id :watt :time]
                 watts))

(defn insert-temps! [temps]
  (insert-multi! :temperature
                 [:device_id :temp :time]
                 temps))


(comment

  (def db {:dbtype "postgresql"
           ;; :dbname "test"
           :user "postgres"
           :password "xxx"
           :host "localhost"
           :port 5432})

  (def ds (jdbc/get-datasource db))
  (def con (.getConnection ds))
  (.close con)

  (jdbc/execute! ds [(format "
CREATE USER fritz;
CREATE DATABASE fritz_data;
GRANT ALL PRIVILEGES ON DATABASE fritz_data TO fritz;
ALTER USER fritz WITH PASSWORD '%s';
" (System/getenv "PGPASSWORD"))])

  )

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(def migration-config {:store                :database
                       :migration-table-name "migrations"
                       :migration-dir        "migrations/"
                       :init-script          "init.sql"
                       :init-in-transaction? false
                       :db db})

;; initialize the database using the 'init.sql' script
;; (migratus/create config "create-fritz")
(comment
 (migratus/init migration-config)
 (migratus/migrate migration-config)
 (migratus/rollback migration-config))

(defn migrate-fwd! []
  (migratus/init migration-config)
  (migratus/migrate migration-config))

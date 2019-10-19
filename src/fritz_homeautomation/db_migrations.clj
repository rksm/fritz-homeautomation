(ns fritz-homeautomation.db-migrations
  (:require [migratus.core :as migratus]))

(def config {:store                :database
             :migration-table-name "migrations"
             :migration-dir        "migrations/"
             :init-script          "init.sql"
             :init-in-transaction? false
             :db {:dbtype "postgresql"
                  :dbname "fritz_data"
                  :user "fritz"
                  :password "fritz"
                  :host "localhost"
                  :port 5432}})

(defn -main []
  (migratus/init config)
  (migratus/migrate config))

;; (migratus/rollback config)
;; (migratus/create config "create-fritz")

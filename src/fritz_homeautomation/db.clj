(ns fritz-homeautomation.db
  (:require [next.jdbc :as jdbc]
            [taoensso.timbre :as timbre]
            [clojure.java.classpath :as cp]
            [clojure.pprint :refer [pprint]]
            [migratus.core :as migratus]))


(comment
  (def db {:dbtype "postgresql"
           ;; :dbname "test"
           :user "postgres"
           :password "tebor1"
           :host "localhost"
           :port 5432})

  (def ds (jdbc/get-datasource db))
  (def con (.getConnection ds))
  (.close con)

  (jdbc/execute! ds ["
CREATE USER fritz;
CREATE DATABASE fritz_data;
GRANT ALL PRIVILEGES ON DATABASE fritz_data TO fritz;
ALTER USER fritz WITH PASSWORD 'fritz';
"]))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(def db {:dbtype "postgresql"
         :dbname "fritz_data"
         :user "fritz"
         :password "fritz"
         :host "localhost"
         :port 5432})

(def ds (jdbc/get-datasource db))
(def con (.getConnection ds))


(jdbc/execute! ds ["
create table if not exists hello (
  id SERIAL,
  name varchar(32),
  email varchar(255)
)"])

(do
 (jdbc/execute! ds ["
  insert into hello(name,email)
  values('Sean Corfield','sean@corfield.org')"])

 (jdbc/execute! ds ["
  insert into hello(name,email)
  values('Robert Krahn','robert@kra.hn'),
        ('Robert Krahn','robert.krahn@gmail.com')"]))

(jdbc/execute! ds ["
WITH uniq AS
  (select distinct on (email) * from hello)
select * from uniq;"])

(jdbc/execute! ds ["
WITH uniq AS
  (select distinct on (email) * from hello)
delete from hello where hello.id not in (select id from uniq);"])

(jdbc/execute! ds ["select distinct on (email) * from hello"])

(pprint (jdbc/execute! ds ["select * from hello"]))
(jdbc/execute! ds ["select * from hello where name = ?" "Robert Krahn"])

(reduce (fn [all it] (conj all (:hello/email it))) [] (jdbc/plan ds ["select * from hello where name = ?" "Robert Krahn"]))

(into #{}
      (map :hello/email)
      (jdbc/plan ds ["select * from hello where name = ?" "Robert Krahn"]))

(jdbc/execute-one! ds ["select * from hello"])


;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

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

;initialize the database using the 'init.sql' script
(migratus/init config)

;apply pending migrations
(migratus/migrate config)
(migratus/rollback config)

;; (migratus/create config "create-fritz")


(ns fritz-homeautomation.main
  (:gen-class)
  (:require [compojure.core :refer [GET POST routes]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.util.response :as response]
            [clojure.java.browse :as browse]
            [fritz-homeautomation.fritz-api :as fritz-api]))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce app-state (atom {:opts {:openbrowser? true
                                 :host "localhost"
                                 :port 9999}
                          :server nil}))

(defn fritz-device-stats []
  (let [sid (fritz-api/get-sid "admin" "down8406")]
    (vec (fritz-api/fetch-stats-of-all-devices sid))))

(defn make-http-app [app-state]
  (let [main-routes
        (routes
         (GET "/fritz-device-stats" [] (pr-str (fritz-device-stats)))
         (GET "/" [] (response/resource-response "public/index.html"))
         (GET "/index.html" [] (response/redirect "/"))
         (route/resources "/" {:root "public"})
         (route/not-found "NOTFOUND "))]
    (-> (handler/api main-routes)
        wrap-stacktrace)))

(defn stop-server!
  [app-state]
  (let [{:keys [server]} @app-state]
    (when server (server))
    (swap! app-state assoc :server nil)))

(defn start-server!
  ([app-state]
   (let [{:keys [server] {:keys [host port]} :opts} @app-state]
     (when server (server))
     (swap! app-state assoc
            :server (run-server (make-http-app app-state)
                                {:ip host :port port :join? false})))))

(defn opts-from-args
  [default-opts args]
  (loop [opts default-opts
         [arg & rest] args]
    (case arg
      nil opts
      ("-p" "--port")        (let [[val & rest] rest] (recur (assoc opts :port (Integer/parseInt val)) rest))
      "--host"               (let [[val & rest] rest] (recur (assoc opts :host val) rest))
      "--dontopen"           (recur (assoc opts :openbrowser? false) rest)
      (recur (update opts :files conj arg) rest))))

(defn -main [& args]
  (let [args-set (set args)
        help? (or (args-set "--help") (args-set "-h"))]
    (when help?
      ;; (println usage)
      (println "help")
      (System/exit 0)))

  (let [{:keys [openbrowser? host port] :as opts}
        (opts-from-args (-> @app-state :opts) args)
        url (str "http://" host ":" port)]
    (swap! app-state assoc :opts opts)
    (start-server! app-state)
    (println (format "Server started at %s" url))
    (when openbrowser?
      (browse/browse-url url))))

(comment
  (stop-server! app-state)
  (start-server! app-state)
  @app-state)

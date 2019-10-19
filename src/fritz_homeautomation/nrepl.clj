(ns fritz-homeautomation.nrepl
  (:require cider.nrepl
            cider.piggieback
            [clojure.pprint :refer [cl-format pprint]]
            figwheel.main.api
            nrepl.core
            nrepl.server
            [refactor-nrepl.middleware :refer [wrap-refactor]]
            sc.api.logging
            [fritz-homeautomation.main :as main]))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce clj-nrepl-server (atom nil))

(defn start-clj-nrepl-server []
  (let [middlewares (conj (map resolve cider.nrepl/cider-middleware)
                          wrap-refactor)
        handler (apply nrepl.server/default-handler middlewares)]
    (pprint middlewares)
    (reset! clj-nrepl-server (nrepl.server/start-server :handler handler :port 7888)))
  (cl-format true "clj nrepl server started~%"))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce cljs-nrepl-server (atom nil))
(defonce cljs-send-msg (atom nil))
(defonce cljs-client (atom nil))
(defonce cljs-client-session (atom nil))

(defn start-cljs-nrepl-server []
  (let [middlewares (conj
                     (map resolve cider.nrepl/cider-middleware)
                     #'cider.piggieback/wrap-cljs-repl)
        handler (apply nrepl.server/default-handler middlewares)]
    (reset! cljs-nrepl-server (nrepl.server/start-server :handler handler :port 7889)))
  (cl-format true "cljs nrepl server started~%"))

(defn start-cljs-nrepl-client []
  (let [conn (nrepl.core/connect :port 7889)
        c (nrepl.core/client conn 1000)
        sess (nrepl.core/client-session c)]
    (reset! cljs-client c)
    (reset! cljs-client-session sess)
    (cl-format true "nrepl client started~%")
    (reset! cljs-send-msg
            (fn [msg] (let [response-seq (nrepl.core/message sess msg)]
                        (cl-format true "nrepl msg send~%")
                        (pprint (doall response-seq)))))))

(defn cljs-send-eval [code]
  (@cljs-send-msg {:op :eval :code code}))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn restart-cljs-server []
  (when @cljs-nrepl-server
    (nrepl.server/stop-server @cljs-nrepl-server))
  (try (figwheel.main.api/stop-all) (catch Exception e (prn e)))

  (start-cljs-nrepl-server)
  (start-cljs-nrepl-client))

(defn -main [& args]
  (sc.api.logging/register-cs-logger :sc.api.logging/log-spy-cs (fn [cs] nil))
  (start-clj-nrepl-server)
  (start-cljs-nrepl-server)

  (main/-main)

  ;; (start-cljs-nrepl-client)
  ;; (cljs-send-eval "(require 'figwheel.main) (figwheel.main/start :fig)")
  )

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment
  (restart-cljs-server))

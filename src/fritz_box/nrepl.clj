(ns fritz-box.nrepl
  (:require cider.nrepl
            [clojure.pprint :refer [cl-format pprint]]
            nrepl.server
            [refactor-nrepl.middleware :refer [wrap-refactor]]))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce clj-nrepl-server (atom nil))

(defn start-clj-nrepl-server []
  (let [middlewares (conj (map resolve cider.nrepl/cider-middleware)
                          wrap-refactor)
        handler (apply nrepl.server/default-handler middlewares)]
    (pprint middlewares)
    (reset! clj-nrepl-server (nrepl.server/start-server :handler handler :port 7888)))
  (cl-format true "clj nrepl server started~%"))

(defn -main [& args]
  (start-clj-nrepl-server))

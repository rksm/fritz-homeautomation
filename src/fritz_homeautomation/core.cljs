(ns ^:figwheel-hooks fritz-homeautomation.core
  (:require [reagent.core :as r]
            [fritz-homeautomation.viz :as viz]))

(enable-console-print!)

(defonce app-state (atom {}))

(defn render []
  (r/render [viz/viz app-state]
            (js/document.querySelector "#app")))

(defn -main []
  (render))

(defonce started (do (-main) true))

(defn ^:after-load hot-rerender []
  (println "hot rerender")
  (render))

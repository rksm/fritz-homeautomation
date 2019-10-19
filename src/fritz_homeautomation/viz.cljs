(ns fritz-homeautomation.viz
  (:require [oz.core :as oz]
            ;; [tick.alpha.api :as t]
            [cljs-time.core :as t]
            [cljs-time.format :as tf]
            [clojure.edn :as edn]
            [cljs.pprint :refer [cl-format]]
            [reagent.core :as reagent]
            [clojure.contrib.humanize :as humanize]))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn fetch-fritz-device-stats []
  (-> (js/fetch "/fritz-device-stats")
      (.then #(.text %))
      (.then edn/read-string)))

(defonce device-stats (do
                        (-> (fetch-fritz-device-stats)
                            (.then #(reset! device-stats %))
                            (.then #(js/console.log "done"))
                            (.catch js/console.error))
                        (reagent/atom nil)))

;; (first @device-stats)

(defn time-seconds-ago [time-now secs]
  (tf/unparse
   (:date-time tf/formatters)
   (t/minus time-now (t/seconds secs))))

#_(cljs.pprint/pprint
 (into {} (for [[key f] (seq tf/formatters)]
            (try [key (tf/unparse f (t/time-now))] (catch js/Error e [key e])))))


(defn render-stats [time-now kind {:keys [interval-seconds unit stat-values] :as stat}]
  (let [duration (humanize/duration (* 1000 interval-seconds))
        unit (name unit)
        title (cl-format nil "~a / ~a" kind duration)
        values (for [[i val] (map-indexed vector stat-values)]
                 {:time (time-seconds-ago time-now (* i interval-seconds))
                  :val val})]
    ^{:key title} [oz/vega-lite {:data {:values values}
                                 :mark "area"
                                 :encoding {:x {:field "time"
                                                :type "temporal"}
                                            :y {:field "val"
                                                :type "quantitative"
                                                :scale {:zero false :nice true}
                                                :title (str unit)}}
                                 :width (- js/document.body.clientWidth 110)
                                 :title title}]))

(defn render-device [time-now {:keys [name watts voltage celsius stats energy-usage-in-wh features] :as device}]
  [:div.device name
   [:ul
    [:li (cl-format nil "power now: ~,2F watts" watts)]
    [:li (cl-format nil "power total: ~,2F kWh" (/ energy-usage-in-wh 1000))]
    [:li (cl-format nil "voltage: ~,2F volts" voltage)]
    [:li (cl-format nil "temperature: ~,1F \u00B0C" celsius)]
    [:li "features:"
     [:ul (doall (map (fn [sym] (let [n (.-name sym)] ^{:key n} [:li n])) features))]]]
   [:div "stats"
    (doall (for [[key stats] stats
                 :let [kind (second (re-find #"(.*)-stats$" (.-name key)))]
                 stat stats]
             (render-stats time-now kind stat)))]])


(defn viz [state]
  [:div (doall (for [device @device-stats]
                 ^{:key (str "fritz-device-" (:id device))}
                 [render-device (t/time-now) device]))])

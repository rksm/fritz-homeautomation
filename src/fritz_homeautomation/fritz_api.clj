(ns fritz-homeautomation.fritz-api
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.data :as data]
            [clojure.zip :as zip]
            [clj-xpath.core :as xpath]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [clojure.set :refer [rename-keys]]
            [clojure.pprint :refer [pprint]])
  (:import java.security.MessageDigest
           java.math.BigInteger
           java.util.Base64))


;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn md5 [^String s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm (.getBytes s "UTF-16le"))]
    (format "%032x" (BigInteger. 1 raw))))

(defn create-session-request-response [password challenge]
  (let [clean-passwd (string/replace password #"[^\x00-\x7F]" ".")
        md5ed (md5 (str challenge "-" clean-passwd))]
    (format "%s-%s" challenge md5ed)))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn req
  ([cmd sid]
   (req cmd sid nil))
  ([cmd sid ain]
   (let [ain (when ain (string/replace ain #" " ""))
         params (cond-> nil
                  ain (assoc :ain ain)
                  cmd (assoc :switchcmd cmd)
                  sid (assoc :sid sid))
         url "http://fritz.box/webservices/homeautoswitch.lua"
         response (client/get url {:query-params params})
         xml (:body response)]
     xml)))

(def default-sid "0000000000000000")

(defn get-sid [user password]
  (let [{:keys [status body]} (client/get "http://fritz.box/login_sid.lua")]
    (if (>= status 300)
      (throw (Exception. (format "fritz.box not up? HTTP status: %s" status)))
      (let [sid (xpath/$x:text "./SessionInfo/SID" body)]
        (if (not= default-sid sid)
          sid
          (let [challenge (xpath/$x:text "./SessionInfo/Challenge" body)
                response (create-session-request-response password challenge)
                url (format "http://fritz.box/login_sid.lua?username=%s&response=%s" user response)
                login-response (client/get url)
                sid (xpath/$x:text "./*/SID" (:body login-response))]
            (if (not= default-sid sid)
              sid
              (throw (Exception. (format "Cannot login: %s" (:body login-response)))))))))))

(defn check-sid [sid]
  (let [{:keys [status body]} (client/get (format "http://fritz.box/login_sid.lua?sid=%s" sid))
        sid (xpath/$x:text "./*/SID" body)]
    (not= default-sid sid)))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
;; devices
;; see https://avm.de/fileadmin/user_upload/Global/Service/Schnittstellen/AHA-HTTP-Interface.pdf

(def features-bits
  {:hanfun-unit        2r1000000000000
   :microfon           2r0100000000000
   :dect-repeater      2r0010000000000
   :outlet             2r0001000000000
   :temperature-sensor 2r0000100000000
   :energy-sensor      2r0000010000000
   :heater             2r0000001000000
   :alarm              2r0000000010000
   :hanfun-device      2r0000000000001})

(defn device-features [function-bitmask]
  (set (let [bits (Integer/parseInt function-bitmask)]
         (for [[key bit-mask] features-bits
               :when (not (zero? (bit-and bits bit-mask)))]
           key))))

(comment
  (Integer/toBinaryString 2944)
  (device-features "2944")
  ;; => #{:temperature-sensor :microfon :outlet :energy-sensor}
)

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(s/def ::features (s/coll-of (set (keys features-bits)) :kind set?))
(s/def ::energy-usage-in-wh (s/and number? #(>= % 0)))
(s/def ::voltage (s/and number? #(>= % 0)))
(s/def ::watts (s/and number? #(>= % 0)))
(s/def ::celsius (s/and number? #(< -273 % 100)))
(s/def ::lock (s/nilable boolean?))
(s/def ::devicelock (s/nilable boolean?))
(s/def ::state (s/nilable boolean?))
(s/def ::present (s/nilable boolean?))
(s/def ::battery-low? (s/nilable boolean?))
(s/def ::battery (s/nilable string?))
(s/def ::alert (s/nilable boolean))
(s/def ::mode #{:auto :manuell})
(s/def ::id string?)
(s/def ::identifier string?)
(s/def ::name string?)
(s/def ::productname string?)
(s/def ::firmware-version string?)
(s/def ::manufacturer #{"AVM"})

(s/def ::device (s/keys :req-un [::id ::identifier ::name ::productname ::manufacturer
                                 ::mode ::state ::features]
                        :opt-un [::lock ::firmware-version ::present ::devicelock
                                 ::alert
                                 ::celsius
                                 ::energy-usage-in-wh ::voltage ::watts]))

(defn zero-one-nil? [val]
  (case val
    "0" false
    "1" true
    nil))

(defn save-parse-float
  ([val]
   (save-parse-float val 1.0))
  ([val ^Float adjust]
   (when val
     (try
       (* (Float/parseFloat val) adjust)
       (catch Exception e
         0.0)))))


(defn process-device-infos [device-list-xml]
  (for [el (xpath/$x "//device" device-list-xml)
        :let [name               (xpath/$x:text? "./name" el)
              present            (-> (xpath/$x:text? "./present" el) zero-one-nil?)
              state              (-> (xpath/$x:text? "./switch/state" el) zero-one-nil?)
              mode               (-> (xpath/$x:text "./switch/mode" el) keyword)
              lock               (-> (xpath/$x:text? "./switch/lock" el) zero-one-nil?)
              devicelock         (-> (xpath/$x:text? "./switch/devicelock" el) zero-one-nil?)
              voltage            (-> (xpath/$x:text? "./powermeter/voltage" el) (save-parse-float 0.001))
              watts              (-> (xpath/$x:text? "./powermeter/power" el) (save-parse-float 0.001))
              energy-usage-in-wh (-> (xpath/$x:text? "./powermeter/energy" el) (save-parse-float))
              celsius            (-> (xpath/$x:text? "./temperature/celsius" el) (save-parse-float 0.1))
              alert              (-> (xpath/$x:text? "./alert" el) zero-one-nil?)
              batterylow         (-> (xpath/$x:text? "./batterylow" el) zero-one-nil?)
              battery            (xpath/$x:text? "./battery" el)
              attrs              (:attrs el)
              features           (device-features (:functionbitmask attrs))]]

    (-> attrs
        (dissoc :functionbitmask)
        (assoc
         :name name
         :present present
         :state state
         :mode mode
         :lock lock
         :devicelock devicelock
         :voltage voltage
         :watts watts
         :energy-usage-in-wh energy-usage-in-wh
         :celsius celsius
         :battery-low? batterylow
         :battery battery
         :alert alert
         :features features)
        (rename-keys {:fwversion :firmware-version}))))


(defn fetch-device-list [sid]
  (process-device-infos (req "getdevicelistinfos" sid)))

(s/fdef fetch-device-stats
  :ret (s/coll-of ::device))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(s/def ::unit #{:watts :volt :celsius :kwH})
(s/def ::stat-values (s/coll-of number?))
(s/def ::interval-seconds (s/and int? #(> % 0)))
(s/def ::stat-count (s/and int? #(>= % 0)))
(s/def ::stats (s/keys :opt-un [::interval-seconds ::stat-count ::stat-values ::unit]))
(s/def ::temperature-stats (s/coll-of ::stats))
(s/def ::energy-stats (s/coll-of ::stats))
(s/def ::power-stats (s/coll-of ::stats))
(s/def ::voltage-stats (s/coll-of ::stats))
(s/def ::device-stats (s/keys :opt-un [::temperature-stats ::energy-stats ::power-stats ::voltage-stats]))

(defn fetch-device-stats
  [sid ain]
  (let [xml (req "getbasicdevicestats" sid ain)
        stats-map {:temperature-stats {:path "/devicestats/temperature/stats" :unit :celsius :convert #(-> % Float/parseFloat (* 0.1))}
                   :energy-stats {:path "/devicestats/energy/stats" :unit :kwH :convert #(-> % Float/parseFloat (* 0.001))}
                   :power-stats {:path "/devicestats/power/stats" :unit :watts :convert #(-> % Float/parseFloat (* 0.01))}
                   :voltage-stats {:path "/devicestats/voltage/stats" :unit :volt :convert #(-> % Float/parseFloat (* 0.001))}}]
    (into {} (for [[key {:keys [path unit convert]}] stats-map]
               (let [values (xpath/$x:text* path xml)
                     attrs (xpath/$x:attrs* path xml)]
                 [key (map (fn [vals {:keys [count grid]}]
                             {:interval-seconds (Integer/parseInt grid)
                              :unit unit
                              :stat-count (Integer/parseInt count)
                              :stat-values (->> (string/split vals #",")
                                                (take-while #(re-find #"[0-9]+" %))
                                                (map #(if (fn? convert) (convert %) (Integer/parseInt %))))})
                           values attrs)])))))

(s/fdef fetch-device-stats
    :ret ::device-stats)

(comment
   (def device-stats (fetch-device-stats sid (-> devices first :identifier)))
   (s/explain ::device-stats device-stats)
   (s/conform ::device-stats device-stats)
  )

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn fetch-stats-of-all-devices [sid]
  (for [{:keys [identifier] :as device} (fetch-device-list sid)
        :let [stats (fetch-device-stats sid identifier)]]
    (assoc device :stats stats)))

(comment

  (do
    (def user (System/getenv "FRITZ_USER"))
    (def password (System/getenv "FRITZ_PASSWORD"))
    (def sid (get-sid user password))
    (check-sid sid)
    (def devices (fetch-device-list sid))
    (def device (first devices)))

  (def all-stats (fetch-stats-of-all-devices sid))

  (count devices)
  (spit "test-2.xml" (req "getdevicelistinfos" sid))
  (spit "test-3.xml" stats)

  ;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

  (require 'clojure.contrib.humanize)
  (clojure.contrib.humanize/duration (* 2678400 1000))
  (clojure.contrib.humanize/duration (* 86400 1000))
  (clojure.contrib.humanize/duration (* 900 1000))
stats


  (let [cmd "getswitchlist"
        url (format "http://fritz.box/webservices/homeautoswitch.lua?switchcmd=%s&sid=%s" cmd sid)
        _ (println url)
        response (client/get url)]
    response)

  (s/explain ::temperatures (:temperatures device-stats))
  (s/explain (s/cat :interval-seconds int? :count int? :values (s/coll-of float?))
             (first (:temperatures device-stats)))

  (def stats (fetch-device-stats sid (-> devices second :identifier)))
  (def stats (fetch-device-stats sid (-> devices first :identifier)))
  (pprint (-> stats :temperature-stats))
  (pprint (-> stats :power-stats))

  (xpath/$x:text* "/devicestats/energy/stats" stats)
  (xpath/$x:attrs* "/devicestats/energy/stats" stats)

  (xpath/$x "/devicestats/temperature" stats)

  ;; in mW
  (req "getswitchpower" sid (:identifier device))

  ;; in wH
  (req "getswitchenergy" sid (:identifier (first devices)))
  (req "getswitchenergy" sid (:identifier (second devices)))
  (req "getswitchname" sid (:identifier device))
  (req "gettemplatelistinfos" sid)


  (-> devices second :identifier)
  (def other-lamp "116570272633")
  (req "setswitchoff" sid other-lamp)
  (req "setswitchon" sid other-lamp)
  (req "setswitchtoggle" sid other-lamp)

  ;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

  (s/explain ::device device)
  (s/conform ::device device)

  (require '[clojure.spec.gen.alpha :as gen])

  (gen/generate (s/gen ::device))
  (gen/generate (s/gen ::device-stats))

  ;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

  )

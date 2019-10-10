(ns fritz-box.main
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.data :as data]
            [clojure.zip :as zip]
            [clj-xpath.core :as xpath]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [clojure.set :refer [rename-keys]])
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


;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn fetch-device-list [sid]
  (process-device-infos (req "getdevicelistinfos" sid)))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn fetch-device-stats [sid ain]
  (let [xml (req "getbasicdevicestats" sid ain)
        stats-map {:temperatures "/devicestats/temperature/stats"
                   :energy "/devicestats/energy/stats"
                   :power "/devicestats/power/stats"
                   :voltage "/devicestats/voltage/stats"}]
    (into {} (for [[key path] stats-map]
               (let [values (xpath/$x:text* path xml)
                     attrs (xpath/$x:attrs* path xml)]
                 [key (map (fn [vals {:keys [count grid]}]
                             {:interval-seconds (Integer/parseInt grid)
                              :count (Integer/parseInt count)
                              :values (->> (string/split vals #",")
                                           (take-while #(re-find #"[0-9]+" %))
                                           (map #(Integer/parseInt %)))})
                           values attrs)])))))


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




(comment

    (def sid (get-sid "admin" "down8406"))

  (do
    (check-sid sid)

    (let [xml (req "getdevicelistinfos" sid)
          devices (process-device-infos xml)]
      (def devices devices)
      (def device (first devices))))

  (count devices)
  (spit "test-2.xml" (req "getdevicelistinfos" sid))
  (spit "test-3.xml" stats)

  ;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

  (let [cmd "getswitchlist"
        url (format "http://fritz.box/webservices/homeautoswitch.lua?switchcmd=%s&sid=%s" cmd sid)
        _ (println url)
        response (client/get url)]
    response)

   (req "getbasicdevicestats" sid (:identifier device))

   (fetch-device-stats sid (-> devices second :identifier))
   (fetch-device-stats sid (-> devices first :identifier))

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
  ;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

  )




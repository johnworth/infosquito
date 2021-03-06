(ns infosquito.messages
  (:require [clojure.tools.logging :as log]
            [infosquito.actions :as actions]
            [infosquito.props :as cfg]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.consumers :as lc]
            [langohr.basic :as lb]
            [langohr.exchange :as le])
  (:import [java.io IOException]
           [org.cyverse.events.ping PingMessages$Pong]
           [com.google.protobuf.util JsonFormat]))

(def ^:const initial-sleep-time 5000)
(def ^:const max-sleep-time 320000)

(defn- sleep
  [millis]
  (try
    (Thread/sleep millis)
    (catch InterruptedException _
      (log/warn "sleep interrupted"))))

(defn- connection-attempt
  [uri millis-to-next-attempt]
  (try
    (rmq/connect {:uri uri})
    (catch IOException e
      (log/error e "unable to establish AMQP connection - trying again in"
                 millis-to-next-attempt "milliseconds")
      (sleep millis-to-next-attempt))))

(defn- next-sleep-time
  [curr-sleep-time]
  (min max-sleep-time (* curr-sleep-time 2)))

(defn- amqp-connect
  "Repeatedly attempts to connect to the AMQP broker, sleeping for increasing periods of
   time when a connection can't be established."
  [uri]
  (->> (iterate next-sleep-time initial-sleep-time)
       (map (partial connection-attempt uri))
       (remove nil?)
       (first)))

(defn- declare-queue
  [ch exchange queue-name]
  (lq/declare ch queue-name
              {:durable     true
               :auto-delete false
               :exclusive   false})
  (doseq [key ["index.all" "index.data" "events.infosquito.#"]]
    (lq/bind ch queue-name exchange {:routing-key key})))

(defn- reindex-handler
  [props ch {:keys [delivery-tag]} _]
  (try
    (actions/reindex props)
    (lb/ack ch delivery-tag)
    (catch Throwable t
      (log/error t "data store reindexing failed")
      (log/warn "requeuing message after" (cfg/get-retry-interval props) "seconds")
      (Thread/sleep (cfg/get-retry-millis props))
      (lb/reject ch delivery-tag true))))

(defn- ping-handler
  [props channel {:keys [delivery-tag routing-key]} msg]
  (try
    (lb/ack channel delivery-tag)
    (log/info (format "[events/ping-handler] [%s] [%s]" routing-key (String. msg)))
    (lb/publish channel (cfg/get-amqp-exchange-name props) "events.infosquito.pong"
      (.print (JsonFormat/printer)
        (.. (PingMessages$Pong/newBuilder)
          (setPongFrom "infosquito")
          (build))))))

(def handlers
  {"index.all"              reindex-handler
   "index.data"             reindex-handler
   "events.infosquito.ping" ping-handler})

(defn- message-router
  [props channel {:keys [routing-key] :as metadata} msg]
  (let [handler (get handlers routing-key)]
    (if-not (nil? handler)
      (handler props channel metadata msg))))

(defn- add-reindex-subscription
 [props ch]
 (let [exchange   (cfg/get-amqp-exchange-name props)
       queue-name (cfg/get-amqp-reindex-queue props)]
   (le/topic ch exchange
     {:durable     (cfg/amqp-exchange-durable? props)
      :auto-delete (cfg/amqp-exchange-auto-delete? props)})
   (declare-queue ch exchange queue-name)
   (lc/blocking-subscribe ch queue-name (partial message-router props))))

(defn- rmq-close
  [c]
  (try
    (rmq/close c)
    (catch Exception _)))

(defn- subscribe
  [conn props]
  (let [ch (lch/open conn)]
    (try
      (add-reindex-subscription props ch)
      (catch Exception e (log/error e "error occurred during message processing"))
      (finally (rmq-close ch)))))

(defn repeatedly-connect
  "Repeatedly attempts to connect to the AMQP broker subscribe to incomming messages."
  [props]
  (let [conn (amqp-connect (cfg/get-amqp-uri props))]
    (log/info "successfully connected to AMQP broker")
    (try
      (subscribe conn props)
      (catch Exception e (log/error e "reconnecting to AMQP in" initial-sleep-time "milliseconds"))
      (finally (rmq-close conn))))
  (Thread/sleep initial-sleep-time)
  (recur props))

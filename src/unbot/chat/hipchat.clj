(ns unbot.chat.hipchat
  (:require
   [clojure.string :as str]
   [rx.lang.clojure.core :as rx]
   [disposables.core :refer [new-disposable* merge-disposables to-disposable]]
   [unbot.types :refer :all])
  (:import
   [org.jivesoftware.smack
    ConnectionConfiguration
    XMPPConnection
    XMPPException
    PacketListener]
   [org.jivesoftware.smack.packet
    Message
    Presence
    Presence$Type]
   [org.jivesoftware.smackx.muc MultiUserChat]))

;; Transform an XMPP Message to a Clojure Map
(defn- message->map [#^Message m]
  (try
    {:category :chat
     :type :receive-message
     :payload (.getBody m)
     :user (-> m (.getFrom) (str/split #"/") second)}
    (catch Exception e
      (println "Received exception while parsing xmpp-message: (ignoring message)")
      (.printStackTrace e)
      {})))

;; Emits a message to the EventBus (event-bus) everytime a
;; chat message is received
(defn- xmpp-message-listener [event-bus room-id]
  (reify PacketListener
    (processPacket [_ packet]
      (let [message0 (message->map packet)
            message
            (merge message0 {:room-id room-id
                             :send-chat-message #(send-chat-message event-bus room-id %)
                             :send-raw-message  #(send-raw-message event-bus %)})]
        (try
          (send-raw-message event-bus message)
          (catch Exception e
            (.onError event-bus e)
            (.printStackTrace e)))))))

;; Setups the listener for HipChat XMPP Connection
(defn- setup-listen-messages [muc event-bus room-id]
  (let [listener (xmpp-message-listener event-bus room-id)]
    (.addMessageListener muc listener)
    (new-disposable* "HipChat receive chat listener"
                     #(.removeMessageListener muc listener))))

;; Setups message emiter for HipChat XMPP Connection
(defn- setup-send-chat-messages [muc event-bus room-id]
  (let [send-messages-observable
        (->> event-bus
             (rx/filter (category-type? :chat :send-message))
             (rx/filter #(= (:room-id %) room-id)))

        subscription (rx/subscribe send-messages-observable
                                   #(.sendMessage muc (:payload %)))]
    (.sendMessage muc "I'm alive!")
    (new-disposable* "HipChat send chat listener"
                     #(.unsubscribe subscription))))

;; Connects to particular HipChat room, and registers EventBus (event-bus)
;; to receive every message and emits it to intersted listeners
(defn- join-hipchat-room [conn room event-bus]
  (let [{room-id :id
         :keys [nick]} room
        muc (MultiUserChat. conn (str room-id "@conf.hipchat.com"))]

    (println "Joining room: " room-id " with nick: " nick)
    (.join muc nick)

    (merge-disposables [(setup-listen-messages muc event-bus room-id)
                        (setup-send-chat-messages muc event-bus room-id)])))

;; Setups the initial Connection to the HipChat XMPP Server
(defn- initialize-xmpp-connection [conn user pass]
  (.connect conn)
  (try
    (.login conn user pass "bot")
    (catch XMPPException e
      (throw (Exception. (str "Failed login with bot credentials for user: " user)))))
  (.sendPacket conn (Presence. Presence$Type/available)))

;; main: Starts a Chat with HipChat
(defn connect-hipchat [{:keys [user pass rooms]} event-bus]
  (let [conn (XMPPConnection. (ConnectionConfiguration. "chat.hipchat.com" 5222))]
    (initialize-xmpp-connection conn user pass)
    (merge-disposables
     [(new-disposable* "HipChat Connection" #(.disconnect conn))
      (merge-disposables (mapv #(join-hipchat-room conn % event-bus) rooms))])))

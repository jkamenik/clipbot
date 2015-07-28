(ns clipbot.chat.hipchat
  (:require
   [clojure.string :as str]
   [rx.lang.clojure.core :as rx]
   [disposables.core :refer [dispose new-disposable* merge-disposables to-disposable]]
   [clipbot.types :refer :all])
  (:import
   [org.jivesoftware.smack ConnectionConfiguration XMPPConnection XMPPException PacketListener]
   [org.jivesoftware.smack.packet Message Presence Presence$Type]
   [org.jivesoftware.smackx.muc MultiUserChat InvitationListener]))

(def ^:private ^:dynamic *join-room-disposables* (atom {}))

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

;; Emits a message to the EventBus (subject) everytime a
;; chat message is received
(defn- xmpp-message-listener [subject room-id]
  (reify PacketListener
    (processPacket [_ packet]
      (let [message0 (message->map packet)
            message  (merge message0 {:room-id room-id
                                      :send-chat-message #(send-chat-message subject %)
                                      :send-raw-message  #(send-raw-message subject %)})]
        (try
          (send-raw-message subject message)
          (catch Exception e
            (.onError subject e)
            (.printStackTrace e)))))))

;; Setups the listener for HipChat XMPP Connection
(defn- setup-listen-messages [muc subject room-id]
  (let [listener (xmpp-message-listener subject room-id)]
    (.addMessageListener muc listener)
    (new-disposable* "HipChat receive chat listener"
                     #(.removeMessageListener muc listener))))

;; Setups message emiter for HipChat XMPP Connection
(defn- setup-send-messages [muc subject]
  (let [send-messages-observable (rx/filter (category-type? :chat :send-message)
                                            subject)
        subscription (rx/subscribe send-messages-observable
                                   #(.sendMessage muc (:payload %)))]
    (new-disposable* "HipChat send chat listener"
                     #(.unsubscribe subscription))))

;; Connects to particular HipChat room, and registers EventBus (subject)
;; to receive every message and emits it to intersted listeners
(defn- join-hipchat-room [conn room nick subject]
  (let [muc (MultiUserChat. conn room)]

    (println "Joining room: " room " with nick: " nick)
    (.join muc nick)

    (merge-disposables [(setup-listen-messages muc subject room)
                        (setup-send-messages muc subject)
                        (new-disposable* "Leave HipChat room"
                                         #(.leave muc))])))
(defn- join-and-register-hipchat-room [conn room-id nick subject]
  (let [disposable (join-hipchat-room conn room-id nick subject)]
    (println "Registering room:" room-id)
    (swap! *join-room-disposables* assoc room-id disposable)
    disposable))

(defn- leave-message? [mention]
  (let [pattern (re-pattern (format "(?i)^%s (leave|go away)!*$" mention))]
   #(re-matches pattern (:payload %))))

(defn- leave-room [room-id]
  (println "going to leave" room-id)
  (when-let [disposable (get @*join-room-disposables* room-id)]
    (println "Leaving room:" room-id)
    (dispose disposable)
    (swap! *join-room-disposables* room-id)))

(defn- handle-leave-requests [mention subject]
  (let [receive-messages-observable (rx/filter (category-type? :chat :receive-message)
                                               subject)
        leave-messages-observable (rx/filter (leave-message? mention)
                                             receive-messages-observable)
        subscription (rx/subscribe receive-messages-observable
                                   #(leave-room (:room-id %)))]

    (new-disposable* "HipChat leave message observable"
                     #(.unsubscribe subscription))))

(defn- xmpp-invitation-listener [nick subject]
  (reify InvitationListener
    (invitationReceived [_ conn room inviter reason password message]
      (join-and-register-hipchat-room conn room nick subject))))

(defn- handle-hipchat-invitations [conn nick subject]
  (let [listener (xmpp-invitation-listener nick subject)]
    (MultiUserChat/addInvitationListener conn listener)
    (new-disposable* "HipChat invitation listener"
                     #(MultiUserChat/removeInvitationListener conn listener))))

;; Setups the initial Connection to the HipChat XMPP Server
(defn- initialize-xmpp-connection [conn user pass]
  (.connect conn)
  (try
    (.login conn user pass "bot")
    (catch XMPPException e
      (throw (Exception. (str "Failed login with bot credentials for user: " user)))))
  (.sendPacket conn (Presence. Presence$Type/available)))

;; main: Starts a Chat with HipChat
(defn connect-hipchat [{:keys [user pass rooms nick mention]} subject]
  (let [conn (XMPPConnection. (ConnectionConfiguration. "chat.hipchat.com" 5222))]
    (initialize-xmpp-connection conn user pass)
    (merge-disposables
     [(new-disposable* "HipChat Connection" #(.disconnect conn))
      (merge-disposables (mapv #(join-and-register-hipchat-room conn
                                                   (str % "@conf.hipchat.com")
                                                   nick
                                                   subject)
                               rooms))
      (handle-hipchat-invitations conn nick subject)
      (handle-leave-requests mention subject)
      (new-disposable* "Reset message-listeners"
                       #(reset! *join-room-disposables* {}))])))

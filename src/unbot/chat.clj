(ns unbot.chat
  (:require
   [clojure.string :as str]
   [disposables.core :refer [merge-disposables]]
   [rx.lang.clojure.core :as rx]
   [rx.lang.clojure.interop :refer [action*]]
   [disposables.core :refer [IDisposable]]
   [unbot.bot :as bot]
   [unbot.chat.hipchat :refer [connect-hipchat]])
  (:import
   [rx Subscription]
   [rx.subscriptions Subscriptions CompositeSubscription]))

;; Public

(defn connect [bot-conf event-bus]
  (let [{:keys [type conf]} (:connection bot-conf)]
    (println "Connecting Bot: " bot-conf)
    (condp = type
      "hipchat" (connect-hipchat conf event-bus)
      :else (throw (Exception. "Unknown chat type")))))

(defn init-chat [bot-configs plugins event-bus]
  (let [bot-disposables        (mapv #(bot/new-bot % event-bus plugins) bot-configs)
        ;; ^ Setups all different subscriptions to Rx streams
        connection-disposables (mapv #(connect % event-bus) bot-configs)
        ;; ^ Setups all connections to Chat resources (socket, etc.)
        ]
    (merge-disposables
     (concat connection-disposables
             bot-disposables))))

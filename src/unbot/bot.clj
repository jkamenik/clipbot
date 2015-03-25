(ns unbot.bot
  (:require
   [disposables.core :refer [merge-disposables new-disposable*]]
   [rx.lang.clojure.core :as rx]
   [unbot.util.rx :as rxu]
   [unbot.types :refer :all])
  (:import
   [rx.subscriptions CompositeSubscription]))

;; Transforms a message category from :chat to the one of
;; the plugin
(defn- message-for-plugin? [regex category-name]
  (fn -message-for-plugin? [{:keys [category type payload] :as msg}]
    (or
     (and (= category :chat)
          (= type :receive-message)
          (re-seq regex payload))
     (= category category-name))))

;; Inner subscribe function that is used in the init function
;; of every plugin
(defn- plugin-subscribe [bot-subscription plugin-id]
  (fn -plugin-subscribe [desc & args]
    (let [subscription (apply rx/subscribe args)]
      (swap! bot-subscription conj
             (new-disposable* (str "Plugin " plugin-id " (" desc ")")
                              #(.unsubscribe subscription))))))

;; Creates the bot disposable, by calling the init function
;; with the subscribe function and the observable you would
;; like to subscribe to.
(defn- create-subscription-disposable [subject rooms plugins]
  (let [bot-subscription (atom [])]
    (doseq [{:keys [regex id init]} plugins
            :let [id-kw (keyword id)
                  observable (->> subject
                                  (rx/map (fn [[timestamp msg]] (assoc msg :timestamp timestamp)))
                                  urx/timestamp
                                  (rx/filter (message-for-plugin? regex id-kw)))]]
      (when init
        (init {:rooms rooms
               :subscribe (plugin-subscribe bot-subscription id)
               :event-bus observable})))
    (merge-disposables @bot-subscription)))

;;
;; Creates a new bot
;; Arguments:
;;   - bot-config: A map with the bot metadata
;;     + id: the name of the bot
;;     + plugins: the name of the plugins to load from available plugins
;;
;;   - subject: The centralized Rx Subject (event bus)
;;   - available-plugins: name of all the available plugins
;;
;; PENDING: a logger
;;
(defn new-bot [{:keys [id plugins] :as bot-config}
              subject
              available-plugins]
  (let [room-list   (mapv :id (get-in bot-config [:connection :conf :rooms]))
        plugin-list (mapv #(get available-plugins %)
                          plugins)]

    (create-subscription-disposable subject
                                    room-list
                                    plugin-list)))

(ns unbot.core
  (:gen-class)
  (:require
   [unbot.chat :as chat]
   [unbot.plugin :as plugin]
   [unbot.nrepl :as nrepl]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [disposables.core :refer [merge-disposables]]
   )
  (:import
   [rx.subjects PublishSubject]))

(def resource-conf
  (io/resource (or (System/getenv "UNBOT_CONFIG_FILE")
                   "config.json")))

(defn read-conf [file]
  (json/parse-string (slurp (or file resource-conf)) true))

(defn start
  ([] (start nil))
  ([conf-file]
   (start conf-file (PublishSubject/create)))
  ([conf-file event-bus]
   (let [conf (read-conf conf-file)
         {bot-configs :bots} conf
         plugins (plugin/load-plugins (mapcat :plugins bot-configs))


         chat-disposable (chat/init-chat bot-configs plugins event-bus)
         nrepl-disposable (nrepl/start-nrepl)]
     (merge-disposables [chat-disposable nrepl-disposable]))))

(defn -main [& [conf-file & args]]
  (start conf-file)
  (loop []
      (Thread/sleep 50000)
    (recur)))

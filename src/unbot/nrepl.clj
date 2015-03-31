(ns unbot.nrepl
  (:require
   [disposables.core :refer [new-disposable*]]
   [clojure.tools.nrepl.server :refer [start-server stop-server]]))

(defonce nrepl-server (atom nil))

(defn start-nrepl []
  (let [server (start-server :port 7777)]
    (reset! nrepl-server server)
    (new-disposable* "nrepl server"
                     #(stop-server server))))

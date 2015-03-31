(ns unbot.plugin
  (:gen-class)
  (:require [clojure.java.io :as io]))

(defonce plugins (atom {}))

(defn read-plugin-file [plugin-name]
  (slurp (io/resource (str "plugins/unbot/plugins/" plugin-name ".clj"))))

(defn load-plugins [plugin-names]
  (doseq [plugin-name plugin-names
          :let [plugin-contents (read-plugin-file plugin-name)]]
    (load-string plugin-contents))
  ;; each load-string should call register-plugin, which is going to update the
  ;; @plugins atom
  @plugins)

(defn register-plugin [plugin]
  (println "Registering Plugin: " plugin)
  (swap! plugins assoc (:id plugin) plugin))

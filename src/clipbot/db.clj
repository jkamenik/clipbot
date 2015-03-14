(ns clipbot.db
  (:refer-clojure :exclude [get set])
  (:require [taoensso.carmine :as redis
                              :refer (wcar)]))

(defmacro ^:private with-conn [& body]
  `(redis/wcar {:pool {} :spec {}} ~@body))

(defn- ns-key [ns key]
  (str ns "/" key))

(defn get [ns key]
  (with-conn (redis/get (ns-key ns key))))

(defn set [ns key val]
  (= "OK"
     (with-conn (redis/set (ns-key ns key) val))))

(defn append [ns key val]
  (with-conn (redis/append (ns-key ns key) val)))

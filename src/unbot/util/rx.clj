(ns unbot.util.rx
  (:refer-clojure :exclude [flatten])
  (:require
   [monads.core :as m]
   [monads.types :as types]
   [rx.lang.clojure.core :as rx])
  (:import
   [rx Observable]))

(m/defmonad observable-m
  (mreturn [_ v] (rx/return v))
  (bind [m mv f]
        (rx/flatmap #(m/run-monad m (f %))
                    mv))

  types/MonadFail
  (fail [m err] (Observable/error err))

  types/MonadPlus
  (mzero [_] (Observable/error (Exception. "mzero")))
  (mplus [_ lr]
         (.onErrorResumeNext (first lr) (second lr))))

(defn to-observable [io-action]
  (fn -to-observable [& args]
    (rx/observable*
     (fn -to-observable [observer]
       (try
         (let [result (apply io-action args)]
           (when-not (rx/unsubscribed? observer)
             (rx/on-next observer result)))
         (catch Exception err
           (rx/on-error observer err)))))))

(defmacro do-observable [& more]
  `(m/run-mdo observable-m ~@more))

(defn mapcat-seq [f source]
  (let [mapcatfn (fn -mapcatfn [observer a]
                   (doseq [b (f a)]
                     (rx/on-next observer b)))]
    (rx/observable*
     (fn -mapcat-seq [observer]
       (let [subscription
             (rx/subscribe source
                           #(mapcatfn observer %)
                           #(rx/on-error observer %)
                           #(rx/on-completed %))]
         (.add observer subscription))))))

(defn flatten [source]
  (mapcat-seq identity source))

(defn timestamp [source]
  (rx/map (fn -timestamp-map [v]
            [(System/currentTimeMillis) v])
          source))

(ns unbot.tmp
  (:require
   [monads.core :refer [Monad] :as monad]
   [clojure.core.match :refer [match]]
   [disposables.core :as disposable]
   [com.unbounce.treajure.io :as tio]
   [rx.lang.clojure.core :as rx]
   [clj-http.client :as http])
  (:import
   [java.util.concurrent Executors]
   [com.spotify.docker.client.messages ContainerConfig]
   [com.spotify.docker.client
    DockerClient
    DefaultDockerClient
    DockerClient$AttachParameter
    DockerCertificates
    ImageNotFoundException]
   [rx.schedulers Schedulers]
   [rx Subscription]
   [rx Observable]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rx scheduler example

(defn scheduler-concurrency-test []
  (let [scheduler    (Schedulers/from (Executors/newFixedThreadPool 2))
        observable   (.observeOn (rx/range 1 10) scheduler)
        subscribe-fn #(do (Thread/sleep (* 1000 1))
                          (println %1 "-"
                                   "input:" %2 "-"
                                   "thread:" (.getId (Thread/currentThread)) "-"
                                   "millis:" (System/currentTimeMillis)))]

    (rx/subscribe observable #(subscribe-fn "subscriber-A" %))
    (rx/subscribe observable #(subscribe-fn "subscriber-B" %))
    (rx/subscribe observable #(subscribe-fn "subscriber-C" %))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Either example

(declare right)
(declare either)

(defrecord Either [inner-value]
  Monad
  (do-result [_ v] (right v))
  (bind [mv f]
    (either (fn [_] mv) f mv)))

(defn right [v]   (Either. [:right v]))
(defn left  [err] (Either. [:left err]))
(defn either [err-fn success-fn val]
  {:pre [(instance? Either val)]}

  (match (:inner-value val)
         ;;
         [:right v]
         (try
           (success-fn v)
           (catch Exception err
             (err-fn err)))

         ;;
         [:left err]
         (err-fn err)))
(def either-m right)

(defn either-monad-example []
  ;; Monadic do for either value using the either-monad
  (monad/do either-m
            [v1 (right 1)
             v2 (right 3)
             ;; v2 (left "error message")
             ]
            (+ v1 v2)))

(defn either-lift-example []
  ;; Make + work on Either land
  ;; ((monad/lift +) (right 1) (right 2))
  ((monad/lift +) (right 1) (left "error happened here")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rx async example (manifold style)

(defn observable-m [v]
  (Observable/just v))

(extend-protocol Monad
  Observable
  (do-result [_ v] (observable-m v))
  (bind [mv f]
    (rx/flatmap f mv)))


;; IO a -> (..Args -> Observable a)
(defn to-observable [io-action]
  (fn [& args]
    (rx/observable*
     (fn -to-observable [observer]
       (try
         (rx/on-next observer (apply io-action args))
         (rx/on-completed observer)
         (catch Exception e
           (rx/on-error observer e)))))))

;; String -> IO String
(defn fetch-weather [city-name]
  (let [response
        (http/get (str "http://api.openweathermap.org/data/2.5/weather?q=" city-name)
                  {:as :auto
                   :accept :json})
        weather  (-> response
                     (get-in [:body :weather])
                     (nth 0))]
    (str city-name " weather is " (:description weather))))


;; We transform a function from (String -> IO String)
;; into (String -> Observable String)
(def fetch-weather-obs
  (to-observable fetch-weather))

;; This is a pure function of signature (String -> String -> String)
(defn vs-string [option-a option-b]
  (str "'" option-a "' vs '" option-b "'"))

;; This is an observable value of type (Observable String)
(defn observable-monad-example [city-1 city-2]
  (monad/do observable-m
            [city-1 (fetch-weather-obs city-1)
             city-2 (fetch-weather-obs city-2)]

            (vs-string city-1 city-2)
            ;; ^ get values out of observables, and apply them
            ;; to a pure function, the result is going to be wrapped
            ;; back to the Observable value under the covers
            ))

;; This is an observable value of type (Observable String)
(defn observable-lift-example [city-1 city-2]
  (let [vs-str-obs (monad/lift vs-string)]
    ;; ^ We lift the pure vs-str function into the Observable context.
    (vs-str-obs
     (fetch-weather-obs city-1)
     (fetch-weather-obs city-2))))

;; On REPL Run:
;;
;; 1) Valid values:
;; (rx/subscribe (observable-lift-example "Vancouver, BC" "Toronto, On")
;;               #(println %)
;;               #(println "ERROR:" %))

;; 2) Invalid values:
;; In this particular example, the service doesn't return a status 404 for
;; cities that don't exist :-(, try changing the URL of the weather service
;; to be invalid, and check how the observable manages that.
;;
;; (rx/subscribe (observable-lift-example "FooFooBar, McMuffin" "Toronto, On")
;;               #(println %)
;;               #(println "ERROR:" %))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Docker Attach Observable implementation


;; Need an RxDisposable to make compatible disposables and
;; Rx.Subscription API
(defrecord RxDisposable [inner-disposable]
  disposable/IDisposable
  (verbose-dispose [_] (disposable/verbose-dispose inner-disposable))

  disposable/IToDisposable
  (to-disposable [_] inner-disposable)

  Subscription
  (unsubscribe [_] (disposable/dispose inner-disposable))
  (isUnsubscribed [_] false))

(defn new-disposable* [desc f]
  (RxDisposable. (disposable/new-disposable* desc f)))

;;;;;;;;;;;;;;;;;;;;

(def default-cmd ["bash", "-c", "for i in {1..10}; do echo $i; sleep 1; done;"])

(def attach-settings
  (into-array
   [DockerClient$AttachParameter/LOGS
    DockerClient$AttachParameter/STDOUT
    DockerClient$AttachParameter/STDERR
    DockerClient$AttachParameter/STREAM]))

(defn new-docker-client []
  (..
   (DefaultDockerClient/fromEnv)
   (connectionPoolSize 10)
   (build)))

;;;;;;;;;;;;;;;;;;;;
;; Either + Rx.Observable example (First Attempt)

;; This implementation uses Either to manage errors on the
;; start-container function, then we can lift the attach-to-container
;; function inside the Either context, in case start-container returns
;; a Right value, the inner value is going to be used in
;; attach-to-container and the result value is going to be of type
;; (Either Exception (Observable String))

;; Once we have this value, we use the either function on the
;; test-docker-observable to execute a function if the returned value
;; (Either Exception (Observable String) is a Left (Exception in this
;; case), and another function if it is Right (Observable in this
;; case).


(defn start-container [client image-name cmd]
  {:post [(instance? Either %)]}
  (try
    (let [config (.. (ContainerConfig/builder)
                     (image image-name)
                     (cmd (into-array cmd))
                     (build))
          container    (.createContainer client config)
          container-id (.id container)]

      (.startContainer client container-id)

      (right
       [container-id
        (new-disposable* (str  "Docker Container " container-id)
                         #(do
                            (println "disposing container")
                            (.killContainer container)))]))

    (catch ImageNotFoundException e
      (left e))))


(defn attach-to-container [client [container-id container-disposable]]
  (rx/observable*
   (fn -attach-to-container [observer]
     (let [attach-disposable (disposable/new-single-assignment-disposable)

           on-next          #(rx/on-next observer (String. %))
           on-completed     #(do (rx/on-completed observer)
                                 (disposable/dispose attach-disposable))

           std-out (tio/split-emit-output-stream \newline on-next on-completed)
           std-err (tio/split-emit-output-stream \newline on-next)

           attach-disposable
           (new-disposable* (str "docker attach (container id: " container-id ")")
                            (fn attach-disposable []
                              (println "disposing attachment")
                              (.close std-out)
                              (.close std-err)))

           _
           (disposable/set-disposable attach-disposable
                                      (disposable/to-disposable attach-disposable))]

       (.add observer attach-disposable)
       (.add observer container-disposable)

       (try
         ;; This will block up until the attach std-out is done (via
         ;; on-completed callback)
         (.. client
             (attachContainer container-id attach-settings)
             (attach std-out std-err))
         (catch Exception e
           (rx/on-error observer e)
           (disposable/dispose attach-disposable)))))))

;; type ImageName = String
;; type ErrorMessage = String
;; type Command   = [String]
;; run-image :: DockerClient -> ImageName -> Command -> Either ErrorMessage (Observable String)
(defn run-image [client image-name cmd]
  {:post [(instance? Either %)] }
  (monad/fmap (partial attach-to-container client)
              (start-container client image-name cmd)))

(defn test-docker-observable [callback]
  (let [client (new-docker-client)

        on-error
        #(callback (str "ERROR: " %))

        subscribe-to-attach-observable
        (fn [observable]
          (rx/subscribe observable
                        callback
                        on-error
                        #(callback "DONE.")))]
    ;; either works as a pattern match function, when value is left
    ;; we are going to call the 1st arg function (on-error in this example)
    ;; in case the value is right, we are going to call the 2nd arg function,
    ;; the third argument is the actual Either value
    (either #(.printStackTrace %) ;; on-error
            subscribe-to-attach-observable
            (run-image client "unbounce/base" default-cmd))))

;;;;;;;;;;;;;;;;;;;;
;; Using Rx only example (Second Attempt)

;; Given that Rx API already provides for error handling, why not
;; just transform the start-container to return (Observable ContainerId),
;; the disposable generated by this function will be handled by the Rx API
;; and in case of an exception, the onError is going to be performed.

;; We use a monadic binding (flatmap) between start-container-1 and
;; attach-to-container-1, this is going to return a stream of output
;; that is going to be returned as is in the run-image-1 function


(defn start-container-1 [client image-name cmd]
  (rx/generator*
   (fn -start-container-1 [observer]
     (let [config (.. (ContainerConfig/builder)
                     (image image-name)
                     (cmd (into-array cmd))
                     (build))
          container    (.createContainer client config)
          container-id (.id container)]

       (.add observer
             (new-disposable* (str  "Docker Container " container-id)
                              #(do
                                 (println "disposing container")
                                 (.killContainer container))))

       (.startContainer client container-id)

       (when-not (rx/unsubscribed? observer)
         (rx/on-next observer container-id))))))


(defn attach-to-container-1 [client container-id]
  (rx/generator*
   (fn -attach-to-container-1 [observer]
     (let [sad-attach-disposable (disposable/new-single-assignment-disposable)

           on-next          #(when-not (rx/unsubscribed? observer)
                               (rx/on-next observer (String. %)))

           on-completed     #(do (rx/on-completed observer)
                                 (disposable/dispose sad-attach-disposable))

           std-out (tio/split-emit-output-stream \newline on-next on-completed)
           std-err (tio/split-emit-output-stream \newline on-next)

           attach-disposable
           (new-disposable* (str "docker attach (container id: " container-id ")")
                            (fn attach-disposable []
                              (println "disposing attachment")
                              (.close std-out)
                              (.close std-err)))

           _
           (disposable/set-disposable sad-attach-disposable
                                      (disposable/to-disposable attach-disposable))]

       ;; add cleanup
       (.add observer attach-disposable)

       ;; blocking up until std-out calls on-completed
       (.. client
           (attachContainer container-id attach-settings)
           (attach std-out std-err))))))

(defn run-image-1 [client image-name cmd]
  (monad/do observable-m
            [ container-id (start-container-1 client image-name cmd)
              output       (attach-to-container-1 client container-id) ]
            output))

(defn test-docker-observable-1 [callback]
  (let [client     (new-docker-client)
        observable (run-image-1 client "unbounce/base" default-cmd)]

    (rx/subscribe observable
                  callback
                  #(callback (str "ERROR: " %))
                  #(callback "DONE."))))

(ns unbot.tmp-2)
;; (ns unbot.tmp-2
;;   (:require
;;    [monads.core :as m]
;;    [monads.types :as types]
;;    [monads.util :refer [lift-m-2]]

;;    [disposables.core :as disposable]
;;    [com.unbounce.treajure.io :as tio]
;;    [rx.lang.clojure.core :as rx]
;;    [rx.lang.clojure.interop :as rxi]

;;    [clj-http.client :as http])
;;   (:import
;;    [java.util Random]
;;    [java.util.concurrent Executors]
;;    [rx Observable]
;;    [rx Subscription]
;;    [rx.schedulers Schedulers]

;;    [com.spotify.docker.client.messages ContainerConfig]
;;    [com.spotify.docker.client
;;     DockerClient
;;     DefaultDockerClient
;;     DockerClient$AttachParameter
;;     DockerCertificates
;;     ImageNotFoundException]
;;    ))

;; (m/defmonad observable-m
;;   (mreturn [_ v] (rx/return v))
;;   (bind [m mv f]
;;         (rx/flatmap #(m/run-monad m (f %))
;;                     mv))

;;   types/MonadFail
;;   (fail [m err] (Observable/error err))

;;   types/MonadPlus
;;   (mzero [_] (Observable/error (Exception. "mzero")))
;;   (mplus [_ lr]
;;          (.onErrorResumeNext (first lr) (second lr))))

;; (defn to-observable [io-action]
;;   (fn -to-observable [& args]
;;     (rx/generator*
;;      (fn -to-observable [observer]
;;        (when-not (rx/unsubscribed? observer)
;;          (rx/on-next observer (apply io-action args)))))))



;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ;; Example transform a cold observable into a shared observable via
;; ;; publish + connect

;; (defn test-ref-count []
;;   (let [random (Random.)
;;         observable (rx/map (fn [n] [n (.nextInt random)]) (rx/range 1 10))
;;         connectable-observable  (.publish observable)]
;;     (rx/subscribe observable #(println "1:" %))
;;     (rx/subscribe observable #(println "2:" %))
;;     ;; inner subscription to observable won't happen up until we call
;;     ;; connect on the observable returned by the publish method
;;     (.connect connectable-observable)))

;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (defn fetch-weather [city-name]
;;   (let [response
;;         (http/get (str "http://api.openweathermap.org/data/2.5/weather?q=" city-name)
;;                   {:as :auto
;;                    :accept :json})
;;         weather  (-> response
;;                      (get-in [:body :weather])
;;                      (nth 0))]
;;     (str city-name " weather is " (:description weather))))


;; (def fetch-weather-obs
;;   (to-observable fetch-weather))

;; (defn vs-string [option-a option-b]
;;   (str "'" option-a "' vs '" option-b "'"))

;; (defn observable-monad-example [city city-2]
;;   (m/run-mdo observable-m
;;    city <- (fetch-weather-obs city)
;;    city-2 <- (fetch-weather-obs city-2)
;;    (if (re-find #"Vancouver, BC" city)
;;      (m/return (vs-string city city-2))
;;      m/mzero)))

;; (defn observable-lift-example [city city-2]
;;   (lift-m-2 vs-string
;;             (fetch-weather-obs city)
;;             (fetch-weather-obs city-2)))

;; (defn test-observable [callback]
;;   (let [observable (observable-monad-example "Vancouver, BC";; "Calgary, AB"
;;                                              "Toronto, ON")]
;;     (rx/subscribe observable
;;                   callback
;;                   #(do
;;                      (.printStackTrace %)
;;                      (callback (str  "ERROR: " %)))
;;                   #(callback "DONE."))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; ;; Need an RxDisposable to make compatible disposables and
;; ;; Rx.Subscription API
;; (defrecord RxDisposable [desc messages-ref inner-disposable]
;;   disposable/IDisposable
;;   (verbose-dispose [_]
;;     (let [messages (disposable/verbose-dispose inner-disposable)]
;;       (reset! messages-ref messages)
;;       messages))

;;   disposable/IToDisposable
;;   (to-disposable [_] inner-disposable)

;;   Subscription
;;   (unsubscribe [self]
;;     (println desc)
;;     (disposable/verbose-dispose self))
;;   (isUnsubscribed [_]
;;     (not (nil? @messages-ref))))

;; (extend-protocol disposable/IDisposable
;;   Subscription
;;   (verbose-dispose [self]
;;     (.unsubscribe self)
;;     []))

;; (defn new-disposable* [desc f]
;;   (RxDisposable.
;;    desc
;;    (atom nil)
;;    (disposable/new-disposable* desc f)))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (def default-cmd ["bash", "-c", "for i in {1..10}; do echo $i; sleep 1; done;"])
;; (def err-cmd ["bash", "-c", "fooofoobarrr"])

;; (def attach-settings
;;   (into-array
;;    [DockerClient$AttachParameter/LOGS
;;     DockerClient$AttachParameter/STDOUT
;;     DockerClient$AttachParameter/STDERR
;;     DockerClient$AttachParameter/STREAM]))

;; (defn new-docker-client []
;;   (..
;;    (DefaultDockerClient/fromEnv)
;;    (connectionPoolSize 10)
;;    (build)))

;; (defn start-container [client image-name cmd]
;;   (rx/generator*
;;    (fn -start-container [observer]
;;      (let [config (.. (ContainerConfig/builder)
;;                       (image image-name)
;;                       (cmd (into-array cmd))
;;                       (build))
;;           container    (.createContainer client config)
;;           container-id (.id container)]

;;        (.add observer
;;              (new-disposable* (str  "Clean Docker Container (image: " image-name
;;                                     ", container-id: " container-id ")")
;;                               #(do
;;                                  (.killContainer client container-id)
;;                                  (.removeContainer client container-id))))

;;        (.startContainer client container-id)

;;        (when-not (rx/unsubscribed? observer)
;;          (rx/on-next observer container-id))))))


;; (defn attach-to-container [client container-id]
;;   (rx/generator*
;;    (fn -attach-to-container [observer]
;;      (let [sad-attach-disposable (disposable/new-single-assignment-disposable)

;;            on-next          #(when-not (rx/unsubscribed? observer)
;;                                (rx/on-next observer [%1 (String. %2)]))

;;            on-completed     #(do
;;                                (rx/on-next observer [:exit-code %])
;;                                (if (= % 0)
;;                                  (rx/on-completed observer)
;;                                  (rx/on-error observer
;;                                               (Exception. (str "Process exit code was non-success: " %))))
;;                                (disposable/dispose sad-attach-disposable))

;;            std-out (tio/split-emit-output-stream \newline (partial on-next :stdout))
;;            std-err (tio/split-emit-output-stream \newline (partial on-next :stderr))

;;            attach-disposable
;;            (new-disposable* (str "Clean Docker Attach (container-id: " container-id ")")
;;                             (fn attach-disposable []
;;                               (.close std-out)
;;                               (.close std-err)))

;;            _
;;            (disposable/set-disposable sad-attach-disposable
;;                                       (disposable/to-disposable attach-disposable))]

;;        ;; add cleanup
;;        (.add observer attach-disposable)

;;        ;; blocking up until std-out calls on-completed
;;        (.. client
;;            (attachContainer container-id attach-settings)
;;            (attach std-out std-err))

;;        (-> client
;;            (.waitContainer container-id)
;;            (.statusCode)
;;            on-completed)))))



;; (defn run-image [client image-name cmd]
;;   (m/run-mdo observable-m
;;     container-id <- (start-container client image-name cmd)
;;     (attach-to-container client container-id)))

;; (defn test-docker-observable [callback]
;;   (let [client     (new-docker-client)
;;         scheduler  (Schedulers/from (Executors/newFixedThreadPool 10))
;;         observable (run-image client "unbounce/fooobaaar" err-cmd)]

;;     (rx/subscribe observable
;;                   callback
;;                   #(callback (str "ERROR: " %))
;;                   #(callback "DONE."))))

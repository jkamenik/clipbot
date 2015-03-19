(ns plugins.unbot.plugins.docker
  (:require
   [monads.core :refer [Monad] :as monad]
   [clojure.string :as str]
   [disposables.core :as disposable]
   [rx.lang.clojure.core :as rx]
   [com.unbounce.treajure.io :as tio]
   [unbot.plugin :as plugin]
   [unbot.types :refer :all])
  (:import
   [java.util.concurrent Executors]
   [rx.schedulers Schedulers]
   [rx Subscription]
   [rx Observable]
   [com.spotify.docker.client.messages ContainerConfig]
   [com.spotify.docker.client
    DockerClient
    DefaultDockerClient
    DockerClient$AttachParameter
    DockerCertificates
    ImageNotFoundException]))

;; credentials come from env vars
;; - DOCKER_HOST=tcp://192.168.59.103:2376
;; - DOCKER_CERT_PATH=/path/to/certs/dir
;; - DOCKER_TLS_VERIFY=1

(def ^:private docker-regex #"^/docker\s+(.*?)\s*$")

(def ^:private docker-tasks
  [{:name "help"
    :args "args parser here"
    :description "Show this help"}
   {:name "run"
    :args "args parser here"
    :description "Run a docker container"}])

(defrecord RxDisposable [inner-disposable]
  disposable/IDisposable
  (verbose-dispose [_] (disposable/verbose-dispose inner-disposable))

  disposable/IToDisposable
  (to-disposable [_] inner-disposable)

  Subscription
  (unsubscribe [_] (disposable/dispose inner-disposable))
  (isUnsubscribed [_] false))

(defn- new-disposable* [desc f]
  (RxDisposable. (disposable/new-disposable* desc f)))

(defn- observable-m [v]
  (Observable/just v))

(extend-protocol Monad
  Observable
  (do-result [_ v] (observable-m v))
  (bind [mv f]
    (rx/flatmap f mv)))

(defn create-docker-client []
  (..
   (DefaultDockerClient/fromEnv)
   (connectionPoolSize 10)
   (build)))

(defn- short-container-id [container-id]
  (subs container-id 0 10))

(defn container-output [client container-id]
  (rx/generator*
   (fn -container-output [observer]
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
                             (println "Closing stdout/stderr from docker container"
                                      (short-container-id container-id))
                             (.close std-out)
                             (.close std-err)))]
       (disposable/set-disposable sad-attach-disposable
                                  (disposable/to-disposable attach-disposable))
       (.add observer attach-disposable)
       (.. client
           (attachContainer container-id
                            (into-array
                             [DockerClient$AttachParameter/LOGS
                              DockerClient$AttachParameter/STDOUT
                              DockerClient$AttachParameter/STDERR
                              DockerClient$AttachParameter/STREAM]))
           (attach std-out std-err))))))

(defn start-container [client image-name command]
  (rx/generator*
   (fn -start-container [observer]
     (let [config (.. (ContainerConfig/builder)
                     (image image-name)
                     (cmd (into-array command))
                     (build))
           container (.createContainer client config)
           container-id (.id container)]
      (.add observer
            (new-disposable* (str "Docker container: " container-id)
                             #(do
                                (println "Killing docker container"
                                         (short-container-id container-id))
                                (.killContainer container))))

      (.startContainer client container-id)

      (when-not (rx/unsubscribed? observer)
         (rx/on-next observer container-id))))))


(defn- category-type [category type]
  (fn -filter-msg [msg]
    (and (= (:category msg)) category
         (= (:type msg) type))))

(def default-cmd ["bash", "-c", "for i in {1..10}; do echo $i; sleep 1; done;"])

(defn start-container-output [client image-name cmd]
  (monad/do observable-m
            [container-id (start-container client image-name cmd)
             output       (container-output client container-id)]
            output))

(defn- docker-run-handler [{:keys [send-chat-message cmd image]
                            :or {cmd default-cmd
                                 image "unbounce/base"}}]
  (let [cmd* (if (string? cmd) (list cmd) cmd)
        ;; TODO create client in init and share across messages
        client (create-docker-client)
        scheduler (Schedulers/from (Executors/newFixedThreadPool 3))
        container-observable (->
                              (start-container-output client image cmd*)
                              (.subscribeOn scheduler)
                              (.publish))]
    (rx/subscribe container-observable
                  send-chat-message
                  #(send-chat-message (str "ERROR: " %))
                  ;; TODO get result of attach and report exit status
                  #(send-chat-message "DONE"))
    (.connect container-observable)))

(defn- docker-help-handler [{:keys [send-chat-message]}]
  (send-chat-message (str "Available commands for /docker\n"
         (str/join "\n"
              (for [{:keys [name description]} docker-tasks]
                (str name " - " description))))))

(defn init-docker-bot [subscribe observable]
  (let [chat-message-events (rx/filter (category-type :chat :receive-message) observable)
        help-events (rx/filter (category-type :docker :help) observable)
        run-events (rx/filter (category-type :docker :run) observable)]
    (subscribe "docker-help" help-events docker-help-handler)
    (subscribe "docker-run" run-events docker-run-handler)))

(plugin/register-plugin
 {:id "docker"
  :regex docker-regex
  :init init-docker-bot})

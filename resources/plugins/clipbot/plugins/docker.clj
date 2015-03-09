(ns clipbot.plugins.docker
  (:require
   [clojure.string :as str]
   [rx.lang.clojure.core :as rx]
   [rx.lang.clojure.interop :refer [action*]]
   [clojure.java.io :as io]
   [clipbot.plugin :as plugin]
   [clipbot.types :refer :all])
  (:import
   [rx.subjects PublishSubject]
   [java.net URI]
   [java.nio.file Paths]
   [java.io
    BufferedReader
    InputStreamReader
    PipedInputStream
    PipedOutputStream]
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

(def docker-regex #"^/docker\s+(.*?)\s*$")

(def RUN
  {:name "run"
   :args "args parser here"
   :description "Run a docker container"})

(def HELP
  {:name "help"
   :args "args parser here"
   :description "Show this help"})

(def docker-tasks
  [HELP RUN])

(defn docker-client []
  (..
   (DefaultDockerClient/fromEnv)
   (connectionPoolSize 10)
   (build)))

(defn- wait-on-ready [reader]
  ;; TODO add timeout
  (if-not (.ready reader)
    (recur reader)))

(defn- buffered-reader->subject  [reader subject]
  (if-let [line (.readLine reader)]
    (do
      (.onNext subject line)
      (recur reader subject))
    (do
      (.close reader)
      (.onCompleted subject))))

(defn input-stream->subject [stream subject]
  (try
    (do
      (let [reader (-> stream
                       (InputStreamReader.)
                       (BufferedReader.))]
        (wait-on-ready reader)
        (buffered-reader->subject reader subject)))
    (catch Exception e (.onError subject e))))

(defn docker-attach [client container-id subject]
  (let [std-out (PipedInputStream.)
        std-err (PipedInputStream.)
        ;; TODO combine std-out/err
        ;; std-out-err (CompositeInputStream. std-out std-err)
        ]
    (future
      (.. client
        (attachContainer container-id
                         (into-array
                           DockerClient$AttachParameter
                           [DockerClient$AttachParameter/LOGS
                            DockerClient$AttachParameter/STDOUT
                            DockerClient$AttachParameter/STDERR
                            DockerClient$AttachParameter/STREAM]))
        (attach (PipedOutputStream. std-out)
                (PipedOutputStream. std-err))))
    (future (input-stream->subject std-out subject))
    (future (input-stream->subject std-err subject))))

(defn docker-run [client image-name command]
  (try
    (let [config (.. (ContainerConfig/builder)
                     (image image-name)
                     (cmd (into-array command))
                     (build))
          container (.createContainer client config)
          container-id (.id container)]
      (.startContainer client container-id)
      container-id)
    (catch ImageNotFoundException e (str "Unable to find image: " image-name))))

(def display-help-event
  (chat-message
    (str "Available commands for ~docker\n"
         (str/join "\n"
              (for [{:keys [name description]} docker-tasks]
                (str name " - " description))))))

(defn parse-chat-message [{:keys [payload send-chat-message] :as ev}]
  (let [[task-name & args] (str/split (->> payload (re-seq docker-regex) first second)
                                   #"\s+") ]
    (merge ev
          (cond
             (= task-name (:name RUN))
             {:category :docker
              :type :run}

             :else
             display-help-event))))

(defn chat-message-parser [{:keys [send-raw-message send-chat-message] :as ev*}]
  (let [ev (parse-chat-message ev*)]
    (send-raw-message ev)))

(defn- category-type [category type]
  (fn -filter-msg [msg]
    (and (= (:category msg)) category
         (= (:type msg) type))))

(defn- docker-run-handler [{:keys [send-chat-message]}]
  (let [subject (PublishSubject/create)
        ;; TODO create client in init and share across messages
        client (docker-client)
        ;; TODO parameritize image and command
        id (docker-run client "unbounce/base" ["bash", "-c", "for i in {1..10}; do echo $i; sleep 1; done;"])]
    (.forEach subject (action* send-chat-message))
    (docker-attach client id subject)))

(defn init-docker-bot [subscribe observable]
  (let [chat-message-events (rx/filter (category-type :chat :receive-message) observable)
        run-events (rx/filter (category-type :docker :run) observable)]
    (subscribe "chat-message-parser" chat-message-events chat-message-parser)
    (subscribe "docker-run" run-events docker-run-handler)))

(plugin/register-plugin
 {:id "docker"
  :regex docker-regex
  :init init-docker-bot})

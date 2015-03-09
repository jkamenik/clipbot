(ns clipbot.plugins.router
  (:refer-clojure :exclude [get char])
  (:use [zetta.core
         :only (always fail-parser do-parser <$> <* *> <|> parse-once)]
        [zetta.parser.seq
         :only (satisfy? not-char char string number word any-token
                get put want-input? letter end-of-input
                spaces skip-whitespaces)]
        [zetta.combinators
         :only (around many many-till many1 sep-by skip-many)])
  (:require
   [clojure.string :as str]
   [rx.lang.clojure.core :as rx]
   [clipbot.plugin :as plugin]
   [clipbot.types :refer :all]))

(defrecord Param [key value])

(def ^:private key-parser
  (around spaces
          (<$> str/join (many-till (not-char \space)
                                   (char \:)))))

(def ^:private letter-or-digit
  (satisfy? #(Character/isLetterOrDigit ^java.lang.Character %)))

(def ^:private slash-or-hyphen
  (<|> (char \/) (char \:)))

;; TODO accept any non-space character
(def ^:private value-parser
  (<$> str/join (many (around spaces (<|> slash-or-hyphen letter-or-digit)))))

(def ^:private param-parser
  (<$> #(Param. %1 %2)
       key-parser
       value-parser))

(def ^:private params-parser
  (sep-by param-parser (char \,)))

(defn parse-input [input]
  (:result (parse-once params-parser input)))

(defn parse-chat-message [{:keys [payload] :as ev}]
  (let [[task-name & args] (str/split (->> payload (re-seq #".*") first second)
                                   #"\s+") ]
    (merge ev
          (cond
             ;; (= task-name (:name PACKAGE))
             ;; {:category :router
             ;;  :type :package
             ;;  :job-name (first args)}

             ;; (= task-name (:name STATUS))
             ;; {:category :router
             ;;  :type :status
             ;;  :job-name (first args)}

             :else
             {}))))


(defn- category-type [category type]
  (fn -filter-msg [msg]
    (and (= (:category msg)) category
         (= (:type msg) type))))

(defn chat-message-parser [{:keys [send-raw-event] :as ev*}]
  (let [ev (parse-chat-message ev*)]
    (send-raw-event ev)))

(defn init-router-bot [subscribe observable]
  (let [chat-message-events (rx/filter (category-type :chat :receive-message) observable)]

    (subscribe "chat-message-parser" chat-message-events chat-message-parser)))

(plugin/register-plugin
  {:id "router"
   :regex #".*"
   :init init-router-bot})

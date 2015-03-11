(ns clipbot.plugins.router
  (:refer-clojure :exclude [get char])
  (:use [zetta.core
         :only (always fail-parser do-parser <$> <* *> <|> parse-once)]
        [zetta.parser.seq
         :only (satisfy? not-char char string number word any-token
                get put want-input? letter end-of-input
                space spaces skip-whitespaces)]
        [zetta.combinators
         :only (around many many-till many1 sep-by1 sep-by skip-many)])
  (:require
   [clojure.string :as str]
   [rx.lang.clojure.core :as rx]
   [clipbot.plugin :as plugin]
   [clipbot.types :refer :all]))

(def ^:private key-parser
  (around spaces
          (<$> str/join (many-till (not-char \space)
                                   (char \:)))))

(def ^:private value-parser
  (<$> str/join (many1 (not-char \,))))

(def ^:private param-parser
  (<$> #(vector (keyword %1) %2)
       key-parser
       value-parser))

(def ^:private params-parser
  (sep-by param-parser (char \,)))

(defn parse-params [input]
  (:result (parse-once params-parser input)))

(def ^:private prefix-parser
  (*> (char \/) word))

(defn merge-params [acc params]
  (if (empty? params)
    acc
    (let [param (first params)]
      (recur (merge acc param)
             (rest params)))))

(def zetta-chat-message-parser
  (do-parser
   [prefix prefix-parser
    :cond [
           (= prefix "docker")
           [
            _ spaces
            category (always prefix)
            type word
            :cond [
                   (= type "run")
                   [params params-parser]

                   (= type "help")
                   [params (always {})]
                   ]
            ]

           (= prefix "ruby")
           [
            _ space
            cmd (<$> str/join (many any-token))
            category (always "docker")
            type (always "run")
            params (always [{:cmd ["ruby" "-e" (str \' cmd \')]}
                            {:image "ruby"}])
            ]
           ]
    ]
   (merge-params {:category (keyword category)
                  :type (keyword type)}
                 params)
   ))

(defn parse-chat-message [{:keys [payload] :as ev}]
  (->>
    payload
    (parse-once zetta-chat-message-parser)
    :result
    (merge ev)))

(defn- category-type [category type]
  (fn -filter-msg [msg]
    (and (= (:category msg)) category
         (= (:type msg) type))))

(defn chat-message-parser [{:keys [send-chat-message send-raw-message] :as ev}]
  ;; (send-chat-message (parse-chat-message ev))
  (send-raw-message (parse-chat-message ev)))

(defn init-router-bot [subscribe observable]
  (let [chat-message-events (rx/filter (category-type :chat :receive-message) observable)]

    (subscribe "chat-message-parser" chat-message-events chat-message-parser)))

(plugin/register-plugin
  {:id "router"
   :regex #".*"
   :init init-router-bot})

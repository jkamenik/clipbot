(ns unbot.plugins.retro
  (:require
   [clojure.string :as str]

   ;; db management
   [korma.core :as sql]
   [korma.db :as db]

   ;; rx magic
   [unbot.util.rx
    :refer [do-observable to-observable mapcat-seq timestamp]
    :as rxu]
   [rx.lang.clojure.core :as rx]

   ;; parser combinating!
   [zetta.core :refer [always fail-parser done?
                       do-parser failure? parse-once
                       <$> *>]]
   [zetta.combinators :as pc]
   [zetta.parser.seq :as p]
   [zetta.parser.string :as ps]

   [unbot.plugin :as plugin])
  (:import
   [rx Observable]
   [java.text ParsePosition SimpleDateFormat]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TYPES

(def RETRO_ENTRY_TYPES
  #{:flowers :delta :plus :idea})

(defrecord RetroEntry   [room user created-at entry-type msg])
(defrecord SetBeginDate [room user created-at begin-date])
(defrecord PrintRetroReport [room send-chat-msg])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PARSERS

(def retro-entry-type-parser
  (do-parser
   entry-type <- p/word
   let entry-type-kw = (keyword entry-type)
   (if (RETRO_ENTRY_TYPES entry-type-kw)
     (always entry-type-kw)
     (fail-parser (str "Retro entry " entry-type " not recognized")))))

(defn retro-entry-parser [{:keys [room-id user timestamp]}]
  (<$> #(RetroEntry. room-id user timestamp %1 %2)
       (*> p/skip-spaces (p/string "#retro")
           p/skip-spaces (p/char \#)
           p/skip-spaces retro-entry-type-parser)
       (*> p/skip-spaces ps/take-rest)))

;;;;;;;;;;;;;;;;;;;;

(def date-parser
  (do-parser
   text <- (ps/take-till #(Character/isWhitespace #^java.lang.Character %))
   (if-let [parsed-date (.parse (SimpleDateFormat. "yyyy/MM/DD")
                                text (ParsePosition. 0))]
     (always parsed-date)
     (fail-parser "Expected date"))))

(defn set-begin-date-parser [{:keys [room-id user timestamp]}]
  (<$> #(SetBeginDate. room-id user timestamp %)
       (*> p/skip-spaces (p/string "#retro")
           p/skip-spaces (p/string "set-begin-date")
           p/skip-spaces date-parser)))


;;;;;;;;;;;;;;;;;;;;

(defn print-retro-report-parser [{:keys [room-id send-chat-message]}]
  (do-parser
   _ <- (*> p/skip-spaces (p/string "#retro")
            p/skip-spaces (p/string "print-report"))
   (always (PrintRetroReport. room-id send-chat-message))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STATE REDUCERS

(defn retro-report-reducer [report retro-entry]
  (-> report
      (update-in
       [(:room retro-entry)
        :by-entry-type
        (:entry-type retro-entry)]
       conj retro-entry)
      (update-in
       [(:room retro-entry) :by-author (:user retro-entry)]
       conj retro-entry)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB

(sql/defentity retro-dates
  (sql/pk :id)
  (sql/table :retro_dates))

(sql/defentity retro-entries
  (sql/pk :id)
  (sql/table :retro_entries))

(defn setup-db []
  (let [db-file-path (System/getenv "RETROCRON_DB")]
    (db/h2 {:db "/tmp/retrocron"})))

(defn fetch-retro-begin-date [db room]
  (first
   (sql/select retro-dates
               (sql/database db)
               (sql/where {:room room})
               (sql/order :created_at :DESC)
               (sql/limit 1))))

(defn fetch-retro-entries-from-date [db begin-date]
  (sql/select retro-entries
              (sql/database db)
              (sql/where (if begin-date
                           {:created_at [>= begin-date]}
                           {}))
              (sql/order :created_at :ASC)))

(defn store-retro-entry [db {:keys [room user entry-type msg created-at]}]
  (sql/insert retro-entries
              (sql/database db)
              (sql/values {:room            room
                           :user            user
                           :entry_type_name entry-type
                           :msg             msg
                           :created_at      created-at})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UTIL OBSERVABLES

(def fetch-retro-begin-date-observable
  (to-observable fetch-retro-begin-date))

(def fetch-retro-entries-from-date-observable
  (to-observable fetch-retro-entries-from-date))

(defn calculate-retro-report-state-observable
  [db begin-date]
  (rx/reduce retro-report-reducer
             {}
             (rxu/flatten
              (fetch-retro-entries-from-date-observable
                 db
                 begin-date))))

(defn mk-report-state-observable
  [report-state0 retro-entries-observable]
  (rx/reductions retro-report-reducer
                 report-state0
                 retro-entries-observable))

(defn retro-reports-observable
  [db begin-date-observable retro-entries-observable]
  (do-observable
   begin-date    <- begin-date-observable
   report-state0 <- (calculate-retro-report-state-observable
                     db
                     begin-date)
   ;; create a new report-state stream reducing the report-state0
   ;; returned by either the db or from a`set-begin-date` command
   ;; delivered on the chat
   (rx/return
    (mk-report-state-observable report-state0
                                retro-entries-observable))))

(defn render-retro-report [room-id report-state]
  (str
   "/code\n"
   (str/join
    "\n"
    (doall
     (for [[type-name entries] (get-in report-state [room-id :by-entry-type])]
       (str "# " (name type-name) "\n"
            (str/join
             "\n"
             (doall
              (for [entry entries]
                (str "  * "
                     (:msg entry)
                     " ("
                     (:user entry)
                     ")"))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HIPCHAT INTEGRATION

(defn merge-timestamp-and-chat-msg [parser [timestamp chat-msg]]
  (let [result (parse-once (parser (assoc chat-msg :timestamp timestamp)) (:payload chat-msg))]
    (if (done? result)
      [(:result result)]
      [])))

(defn timestamped-parse [parser event-bus]
  (->> event-bus
       timestamp
       (mapcat-seq #(merge-timestamp-and-chat-msg parser %))))

(defn init-retro [subscribe event-bus]
  (let [report-state
        (atom nil)

        ;; db
        ;; (setup-db)

        ;; chat-message to set-begin-date
        ;; set-begin-date-cmd-observable
        ;; (timestamped-parse set-begin-date-parser
        ;;                    event-bus)

        print-retro-cmd-observable
        (timestamped-parse print-retro-report-parser
                           event-bus)

        ;; chat-message to retro-entry
        retro-entries-observable
        (timestamped-parse retro-entry-parser
                           event-bus)

        ;; begin-date-observable
        ;; set-begin-date-cmd-observable

        ;; reports-observable
        ;; (Observable/switchOnNext
        ;;  (retro-reports-observable db
        ;;                            begin-date-observable
        ;;                            retro-entries-observable))

        reports-observable
        (mk-report-state-observable {}
                                    retro-entries-observable)
        ]

    (subscribe "event bus"
               event-bus
               #(println "event-bus =>" %))

    #_(subscribe "set-begin-date commands"
                 set-begin-date-cmd-observable
                 #(println "set-begin-state =>" %))

    #_(subscribe "store-retro entries"
                 retro-entries-observable
                 #(do
                    (println "retro-entry =>" %)
                    (store-retro-entry db %)))

    (subscribe "print-retro commands"
               print-retro-cmd-observable
               (fn send-retro-report [{:keys [room send-chat-msg]}]
                 (send-chat-msg
                  (render-retro-report room @report-state))))

    (subscribe "retro-report reducer"
               reports-observable
               #(do
                  (println "report-state =>" %)
                  (reset! report-state %)))))

(plugin/register-plugin
 {:id    "retro"
  :regex #"\#retro"
  :init init-retro})

(defn test-a []
  (let [event-bus  (rx.subjects.PublishSubject/create)
        disposable (init-retro
                    (fn [desc & args]
                      (println desc)
                      (apply rx/subscribe args))
                    event-bus)]

    (rx/on-next event-bus
                {:user "Roman"
                 :room-id "App Team"
                 :payload "#retro "})

    (rx/on-next event-bus
                {:user "Chris"
                :room-id "App Team"
                :payload "#retro #idea use Haskell on a project"})

    (rx/on-next event-bus
                {:user "Brian"
                 :room-id "App Team"
                 :payload  "#retro #plus using moar clojure"})

    (rx/on-next event-bus
                {:user "James"
                 :room-id "App Team"
                 :payload  "#retro #idea bring donuts every morning"})

    (rx/on-next event-bus
                {:user "Roman"
                 :room-id "App Team"
                 :payload "#retro print-report"
                 :send-chat-msg #(println %)})

    [disposable event-bus]))

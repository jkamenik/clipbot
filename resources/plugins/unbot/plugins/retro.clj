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
   [zetta.core :as zetta :refer [<$> *>]]
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

(defrecord RetroEntry       [room author created-at entry-type msg])
(defrecord StartSprint      [room author created-at])
(defrecord PrintRetroReport [room send-chat-message])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PARSERS

(def retro-entry-type-parser
  (zetta/do-parser
   entry-type <- p/word
   let entry-type-kw = (keyword entry-type)
   (if (RETRO_ENTRY_TYPES entry-type-kw)
     (zetta/always entry-type-kw)
     (zetta/fail-parser (str "Retro entry " entry-type " not recognized")))))

(defn retro-entry-cmd-parser [{:keys [room-id user timestamp]}]
  (<$> #(map->RetroEntry {:room room-id
                          :author user
                          :created-at (java.util.Date. timestamp)
                          :entry-type %1
                          :msg %2 })
       (*> p/skip-spaces (p/string "#retro")
           p/skip-spaces (p/char \#)
           p/skip-spaces retro-entry-type-parser)
       (*> p/skip-spaces ps/take-rest)))

;;;;;;;;;;;;;;;;;;;;

;; (def date-parser
;;   (zetta/do-parser
;;    text <- (ps/take-till #(Character/isWhitespace #^java.lang.Character %))
;;    (if-let [parsed-date (.parse (SimpleDateFormat. "yyyy/MM/DD")
;;                                 text (ParsePosition. 0))]
;;      (zetta/always parsed-date)
;;      (zetta/fail-parser "Expected date"))))

(defn start-sprint-cmd-parser [{:keys [room-id author timestamp]}]
  (<$> #(StartSprint. room-id author (java.util.Date. timestamp))
       (*> p/skip-spaces (p/string "#retro")
           p/skip-spaces (p/string "start-sprint"))))


;;;;;;;;;;;;;;;;;;;;

(defn print-retro-report-cmd-parser [{:keys [room-id send-chat-message]}]
  (zetta/do-parser
   _ <- (*> p/skip-spaces (p/string "#retro")
            p/skip-spaces (p/string "print-report"))
   (zetta/always (PrintRetroReport. room-id send-chat-message))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STATE REDUCERS

(defn sprint-report-reducer [report retro-entry]
  (-> report
      (update-in
       [(:room retro-entry)
        :by-entry-type
        (:entry-type retro-entry)]
       conj retro-entry)
      (update-in
       [(:room retro-entry) :by-author (:author retro-entry)]
       conj retro-entry)))

(defn sprint-report-per-room-reducer [report msg]
  (cond
    ;; when a sprint starts, we empty the report
    (instance? msg StartSprint)
    (assoc report (:room msg) {})

    ;; when a retro msg is sent, we add it to the report
    (instance? msg RetroEntry)
    (sprint-report-reducer report msg)

    ;; if we receive a command we don't understand, we just ignore it
    :else report))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB

(sql/defentity retro-sprints
  (sql/pk :id)
  (sql/table :retro_sprints))

(sql/defentity retro-entries
  (sql/pk :id)
  (sql/table :retro_entries))

(defn setup-db []
  (let [db-file-path (System/getenv "RETROCRON_DB")]
    (db/h2 {:db "/tmp/retrocron"})))

;; QUERIES

(defn fetch-initial-sprint-begin-date [db]
  (reduce
   #(update-in %1 [(:room %2)] (constantly (:max_created_at %2)))
   {}
   (sql/select retro-sprints
              (sql/database db)
              (sql/modifier "DISTINCT")
              (sql/fields :room)
              (sql/aggregate (max :created_at) :max_created_at)
              (sql/group :room))))

(defn fetch-retro-entries-from-date [db room begin-date]
  (map
   #(-> %
        (assoc :created-at (:created_at %))
        (assoc :entry-type (:entry_type_name %)))
   (sql/select retro-entries
               (sql/database db)
               (sql/where {:room room
                           :created_at [>= begin-date]})
               (sql/order :created_at :ASC))))

(defn fetch-initial-sprint-report [db sprint-dates-per-room]
  (apply
   merge
   (for [[room begin-date] sprint-dates-per-room]
     (reduce
      sprint-report-reducer
      {}
      (fetch-retro-entries-from-date db room begin-date)))))

;; COMMANDS

(defn store-new-sprint
  [db ^StartSprint {:keys [room author created-at]}]
  (sql/insert retro-sprints
              (sql/database db)
              (sql/values {:room   room
                           :author author
                           :created_at created-at})))

(defn store-retro-entry
  [db ^RetroEntry {:keys [room author entry-type msg created-at]}]
  (sql/insert retro-entries
              (sql/database db)
              (sql/values {:room            room
                           :author          author
                           :entry_type_name (name entry-type)
                           :msg             msg
                           :created_at      created-at})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UTIL OBSERVABLES

(defn mk-fetch-initial-sprint-dates-observable [& args]
  (.publish
   (apply (to-observable fetch-initial-sprint-begin-date) args)))

(def fetch-initial-sprint-report-observable
  (to-observable fetch-initial-sprint-report))

(defn mk-report-state-observable
  [sprint-report retro-entry-cmd-observable]
  (rxu/scan sprint-report-reducer
            sprint-report
            retro-entry-cmd-observable))

(defn render-retro-report [room-id sprint-report-per-room-var]
  (str
   "/code\n"
   (str/join
    "\n"
    (doall
     (for [[type-name entries] (get-in sprint-report-per-room-var [room-id :by-entry-type])]
       (str "# " (name type-name) "\n"
            (str/join
             "\n"
             (doall
              (for [entry entries]
                (str "  * "
                     (:msg entry)
                     " ("
                     (:author entry)
                     ")"))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HIPCHAT INTEGRATION

(defn parse-filter
  ([parse-fn source]
   (parse-filter parse-fn (constantly nil) source))
  ([parse-fn on-error source]
   (let [filter-parse-result
         (fn [entry]
           (let [result (parse-fn entry)]
             (if (zetta/done? result)
               [(:result result)]
               ;; else
               (do
                 (on-error result)
                 []))))]
     (rxu/mapcat-seq filter-parse-result source))))


(defn mk-sprint-dates-per-room-observable
  "Reduces StartSprint commands into the sprint-dates-per-room Map"
  [fetch-initial-sprint-dates-observable
   start-sprint-cmd-observable]
  (do-observable
   init-sprint-dates-per-room <- fetch-initial-sprint-dates-observable
   (rxu/scan #(assoc %1 (:room %2) (:created-at %2))
             init-sprint-dates-per-room
             start-sprint-cmd-observable)))

(defn mk-sprint-report-per-room-observable
  [db
   sprint-dates-per-room-observable
   start-sprint-and-entries-cmd-observable]

  (do-observable
   sprint-dates-per-room  <- (rx/first sprint-dates-per-room-observable)
   init-sprint-report-per-room <- (fetch-initial-sprint-report-observable db
                                                                          sprint-dates-per-room)
   (rxu/scan sprint-report-per-room-reducer
             init-sprint-report-per-room
             start-sprint-and-entries-cmd-observable)))

(defn init-retro [{:keys [subscribe event-bus]}]
  (let [sprint-dates-per-room-var
        (atom {})

        sprint-report-per-room-var
        (atom {})

        db
        (setup-db)

        ;; db interaction observables

        fetch-initial-sprint-dates-observable
        (mk-fetch-initial-sprint-dates-observable db)

        ;; chat channel observables

        start-sprint-cmd-observable
        (parse-filter #(zetta/parse-once (start-sprint-cmd-parser %)
                                         (:payload %))
                      event-bus)

        print-retro-report-cmd-observable
        (parse-filter #(zetta/parse-once (print-retro-report-cmd-parser %)
                                         (:payload %))
                      event-bus)

        retro-entry-cmd-observable
        (parse-filter #(zetta/parse-once (retro-entry-cmd-parser %)
                                         (:payload %))
                      event-bus)

        ;; composition of observables from both db and chat channel

        sprint-dates-per-room-observable
        (mk-sprint-dates-per-room-observable fetch-initial-sprint-dates-observable
                                             start-sprint-cmd-observable)

        sprint-report-per-room-observable
        (mk-sprint-report-per-room-observable
         db
         sprint-dates-per-room-observable
         (rx/merge start-sprint-cmd-observable
                   retro-entry-cmd-observable))

        ]

    (subscribe "event bus (debug)"
               event-bus
               #(println "event-bus =>" %)
               #(.printStackTrace %))

    (subscribe "store-retro entries"
               retro-entry-cmd-observable
               #(do
                  (println "storing entry =>" %)
                  (store-retro-entry db %))
               #(.printStackTrace %))

    (subscribe "print-retro command handling"
               print-retro-report-cmd-observable
               (fn send-retro-report [{:keys [room send-chat-message]}]
                 (send-chat-message
                  (render-retro-report room @sprint-report-per-room-var)))
               #(.printStackTrace %))

    (subscribe "sprint-dates-per-room"
               sprint-dates-per-room-observable
               #(do
                  (println "sprint-dates-per-room" %)
                  (reset! sprint-dates-per-room-var %))
               #(.printStackTrace %))

    (subscribe "sprint-report-per-room"
               sprint-report-per-room-observable
               #(do
                  (println "sprint-report-per-room" %)
                  (reset! sprint-report-per-room-var %))
               #(.printStackTrace %))

    (.connect fetch-initial-sprint-dates-observable)))

(plugin/register-plugin
 {:id    "retro"
  :regex #"\#retro"
  :init init-retro})

(defn test-a []
  (let [event-bus    (rx.subjects.PublishSubject/create)
        current-time (System/currentTimeMillis)
        disposable (init-retro
                    {:subscribe (fn [desc & args]
                                  #_(println desc)
                                  (apply rx/subscribe args))
                     :rooms ["App Team" "Cobras"]
                     :event-bus event-bus})]


    ;; (rx/on-next event-bus
    ;;             {:user "Chris"
    ;;              :room-id "App Team"
    ;;              :timestamp current-time
    ;;              :payload "#retro #idea use Haskell on a project"})

    ;; (rx/on-next event-bus
    ;;             {:user "Brian"
    ;;              :room-id "App Team"
    ;;              :timestamp (+ 5000 current-time)
    ;;              :payload  "#retro #plus using moar clojure"})

    ;; (rx/on-next event-bus
    ;;             {:user "James"
    ;;              :room-id "App Team"
    ;;              :timestamp (+ 60000 current-time)
    ;;              :payload  "#retro #idea bring donuts every morning"})

    ;; (rx/on-next event-bus
    ;;             {:user "Roman"
    ;;              :room-id "App Team"
    ;;              :payload "#retro print-report"
    ;;              :timestamp (+ 120000 current-time)
    ;;              :send-chat-message #(println %)})

    ;; (rx/on-next event-bus
    ;;             {:user "Tavis"
    ;;              :room-id "Cobras"
    ;;              :payload "#retro #idea other message"
    ;;              :timestamp current-time})

    [disposable event-bus]))

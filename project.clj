(defproject unbot "0.0.1-SNAPSHOT"
  :description "Chat Bot developed by Unbounce"
  :url "http://dev.unbounce.com"
  :main unbot.core
  :resource-paths ["resources" "resources/plugins"]
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repl-options {:init-ns dev}
  :plugins [[ragtime/ragtime.lein "0.3.8"]]
  :ragtime {:migrations ragtime.sql.files/migrations
            :database "jdbc:h2:file:/tmp/retrocron"}
  :dependencies [[org.clojure/clojure "1.6.0"]

                 ;; TODO: move dependencies to a different
                 ;; unbounce dependencies
                 [bwo/monads "0.2.2"]
                 [com.unbounce/treajure "1.0.0"]
                 [com.spotify/docker-client "2.7.7"]
                 [io.reactivex/rxclojure "1.0.0"]
                 [org.clojure/core.match "0.2.2"]
                 [org.van-clj/disposables "0.2.0"]
                 [org.van-clj/zetta-parser "0.1.0-SNAPSHOT"]

                 ;; retro plugin
                 [korma "0.4.0"]
                 [com.h2database/h2 "1.3.170"]
                 [clj-liquibase "0.5.2"]
                 [ragtime "0.3.8"]

                 [jivesoftware/smack "3.1.0"]
                 [jivesoftware/smackx "3.1.0"]
                 [net.java.dev.rome/rome "1.0.0"]

                 [clj-http "0.9.2"],
                 [cheshire "5.2.0"],
                 [compojure "1.1.5"]
                 [http-kit "2.1.16"]
                 [clojail "1.0.6"]
                 [tentacles "0.2.5"]
                 [pandect "0.3.4"]
                 [hiccup "1.0.5"]
                 [ring-basic-authentication "1.0.5"]])

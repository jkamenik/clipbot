(defproject clipbot "0.1.0-SNAPSHOT"
  :description "Clojure HipChat Bot"
  :url "http://bryangilbert.com"
  :main clipbot.core
  :plugins [[lein-ring "0.8.6"]]
  :resource-paths ["resources" "resources/plugins"]
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repl-options {:init-ns dev}
  :dependencies [[org.clojure/clojure "1.6.0"]

                 ;; TODO: move dependencies to a different
                 ;; unbounce dependencies
                 [org.clojure/core.match "0.2.2"]
                 [io.reactivex/rxclojure "1.0.0"]
                 [com.rallydev/clj-jenkins "0.1.5" :exclusions [org.clojure/clojure]]
                 [net.clojure/monads "1.0.0"]
                 [org.van-clj/disposables "0.2.0"]

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

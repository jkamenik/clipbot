(ns clipbot.plugins.router-test
  (:require [clojure.test :refer :all]
            [clipbot.plugins.router :as router]))

(deftest parse-chat-message-test
  (are [input expected] (= (router/parse-chat-message {:payload input})
                           expected)
       "/docker run"
       {:category :docker
        :type :run
        :payload "/docker run"}

       "/docker run image: unbounce/base"
       {:category :docker
        :type :run
        :image "unbounce/base"
        :payload "/docker run image: unbounce/base"}

       "/docker run image: unbounce/base, cmd: echo hello"
       {:category :docker
        :type :run
        :image "unbounce/base"
        :cmd "echo hello"
        :payload "/docker run image: unbounce/base, cmd: echo hello"}

       "/docker  run  image:  unbounce/base ,  cmd:  echo hello "
       {:category :docker
        :type :run
        :image "unbounce/base "
        :cmd "echo hello "
        :payload "/docker  run  image:  unbounce/base ,  cmd:  echo hello "}


       "/ruby puts hello"
       {:category :docker
        :type :run
        :image "ruby"
        :cmd ["ruby" "-e" (str "'puts hello'")]
        :payload "/ruby puts hello"}
       ))

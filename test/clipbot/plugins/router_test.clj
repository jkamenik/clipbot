(ns clipbot.plugins.router-test
  (:require [clojure.test :refer :all]
            [clipbot.plugins.router :as router]))

(deftest test-params-parser
  (are [input expected] (= (router/parse-input input) expected)
       "key: value:latest, key-one: value/1" '({:key "key"
                                               :value "value:latest"}
                                              {:key "key-one"
                                               :value "value/1"})))

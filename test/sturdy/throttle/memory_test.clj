(ns sturdy.throttle.memory-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sturdy.throttle.core :as core]
   [sturdy.throttle.memory :as memory]))

(set! *warn-on-reflection* true)

(deftest ip-limiter-test
  (let [limiter (memory/make-ip-limiter {:limit-per-second 5 :window-ms 100})
        ip "127.0.0.1"]
    (testing "Allows up to limit"
      (dotimes [_ 5]
        (is (true? (core/admit? limiter ip)))))

    (testing "Rejects over limit"
      (is (false? (core/admit? limiter ip))))

    (testing "Rejects over limit (2)"
      (is (false? (core/admit? limiter ip))))

    (testing "Resets after window expires"
      ;; Wait a bit longer than 100ms to ensure the window expires
      (Thread/sleep 110)
      (is (true? (core/admit? limiter ip))))

    (testing "Other IPs are not affected"
      ;; Currently ip is at 1 (from the reset above)
      ;; Use another 4 to hit the limit
      (dotimes [_ 4]
        (is (true? (core/admit? limiter ip))))
      (is (false? (core/admit? limiter ip)))
      (is (true? (core/admit? limiter "192.168.1.1"))))))

(deftest ip-limiter-configuration-validation-test
  (testing "Accepts defaults and positive integer boundary values"
    (is (true? (core/admit? (memory/make-ip-limiter {}) "default-config")))
    (let [limiter (memory/make-ip-limiter {:limit-per-second 1
                                           :window-ms 10000})]
      (is (true? (core/admit? limiter "boundary")))
      (is (false? (core/admit? limiter "boundary"))))
    (is (true? (core/admit? (memory/make-ip-limiter {:limit-per-second 5
                                                      :window-ms 1})
                            "boundary-window"))))

  (doseq [[option value] [[:limit-per-second nil]
                          [:limit-per-second 0]
                          [:limit-per-second -1]
                          [:limit-per-second 1.5]
                          [:limit-per-second "5"]
                          [:window-ms nil]
                          [:window-ms 0]
                          [:window-ms -1]
                          [:window-ms 1.5]
                          [:window-ms "1000"]]]
    (testing (str "Rejects invalid " option " value " (pr-str value))
      (try
        (memory/make-ip-limiter (assoc {:limit-per-second 5
                                        :window-ms 1000}
                                       option value))
        (is false "Expected invalid configuration to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= option (get-in (ex-data e) [:data :option])))
          (is (= value (get-in (ex-data e) [:arg :value]))))))))

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

(ns sturdy.throttle-test
  (:require [clojure.test :refer :all]
            [sturdy.throttle :as throttle]
            [sturdy.throttle.core :as core]))

(deftype MockLimiter [admit-fn]
  core/RateLimiter
  (admit? [_ key]
    (admit-fn key)))

(deftest wrap-ip-rate-limit-test
  (let [handler (fn [_] {:status 200 :body "OK"})
        extract-ip :ip
        limiter (->MockLimiter (fn [ip] (not= ip "blocked-ip")))
        middleware (throttle/wrap-ip-rate-limit handler limiter extract-ip)]

    (testing "Passes through allowed IP"
      (let [res (middleware {:ip "allowed-ip"})]
        (is (= 200 (:status res)))))

    (testing "Blocks disallowed IP"
      (let [res (middleware {:ip "blocked-ip"})]
        (is (= 429 (:status res)))
        (is (= "" (:body res)))))

    (testing "Passes through if IP extraction fails"
      (let [res (middleware {})]
        (is (= 200 (:status res)))))))

(deftest wrap-quota-rate-limit-test
  (let [handler (fn [_] {:status 200 :body "OK"})
        extract-key :org-id
        limiter (->MockLimiter (fn [k] (not= k "blocked-org")))
        error-response {:status 429 :body "Custom Error"}
        middleware (throttle/wrap-quota-rate-limit handler limiter extract-key error-response)
        middleware-default (throttle/wrap-quota-rate-limit handler limiter extract-key nil)]

    (testing "Passes through allowed org"
      (let [res (middleware {:org-id "allowed-org"})]
        (is (= 200 (:status res)))))

    (testing "Blocks disallowed org with custom error"
      (let [res (middleware {:org-id "blocked-org"})]
        (is (= 429 (:status res)))
        (is (= "Custom Error" (:body res)))))

    (testing "Blocks disallowed org with default error"
      (let [res (middleware-default {:org-id "blocked-org"})]
        (is (= 429 (:status res)))
        (is (= "Rate limit exceeded. Please try again later." (:body res)))))

    (testing "Passes through if key extraction fails"
      (let [res (middleware {})]
        (is (= 200 (:status res)))))))

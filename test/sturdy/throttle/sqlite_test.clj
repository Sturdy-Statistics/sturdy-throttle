(ns sturdy.throttle.sqlite-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [sturdy.sqlite.test :refer [with-test-db]]
   [sturdy.throttle.core :as core]
   [sturdy.throttle.sqlite :as sqlite]
   [sturdy.throttle.test-support :refer [with-quiet-logging]]))

(set! *warn-on-reflection* true)

(deftest sqlite-window-bucket-test
  (testing "Window size maps to minute buckets"
    (is (= 1 (sqlite/window-bucket-count 1)))
    (is (= 1 (sqlite/window-bucket-count sqlite/minute-ms)))
    (is (= 2 (sqlite/window-bucket-count (inc sqlite/minute-ms)))))

  (testing "Default window preserves the previous hourly behavior"
    (let [now-ms (* 100 sqlite/minute-ms)]
      (is (= (* 41 sqlite/minute-ms)
             (sqlite/oldest-live-bucket-ms now-ms)))))

  (testing "Custom window controls oldest live bucket"
    (let [now-ms (* 100 sqlite/minute-ms)]
      (is (= (* 99 sqlite/minute-ms)
             (sqlite/oldest-live-bucket-ms now-ms (* 2 sqlite/minute-ms))))
      (is (= (* 96 sqlite/minute-ms)
             (sqlite/oldest-live-bucket-ms now-ms (* 5 sqlite/minute-ms)))))))

(deftest sqlite-quota-limiter-factory-test
  (let [db-dir (str (fs/create-temp-dir {:prefix "sturdy-throttle-factory-test-"}))
        db-name "factorydb"]
    (with-quiet-logging
      (let [limiter (sqlite/make-quota-limiter {:db-dir db-dir
                                                :db-name db-name
                                                :limit 5
                                                :prune-every nil})]
        (try
          (is (true? (core/admit? limiter "factory-org")))
          (finally
            (sqlite/close-limiter limiter)
            (fs/delete-tree db-dir)))))))

(deftest sqlite-quota-limiter-limit-test
  (let [org-id (random-uuid)]
    (with-quiet-logging
      (with-test-db [sys "test-limit-db" {:classpath-prefix "sturdy-throttle-migrations"}]
        (let [limiter (sqlite/->SQLiteQuotaLimiter sys {:write-fn (:write-fn sys)
                                                        :limit 5
                                                        :prune-every nil})]
          (testing "Allows up to limit (raw UUID)"
            (dotimes [_ 5]
              (is (true? (core/admit? limiter org-id)))))

          (testing "Rejects over limit"
            (is (false? (core/admit? limiter org-id))))

          (testing "Different org gets its own limit"
            (let [other-org (random-uuid)]
              (is (true? (core/admit? limiter other-org))))))))))

(deftest sqlite-quota-limiter-window-test
  (let [org-id (random-uuid)
        base-ms (* 100 sqlite/minute-ms)]
    (with-quiet-logging
      (with-test-db [sys "test-window-db" {:classpath-prefix "sturdy-throttle-migrations"}]
        (let [limiter (sqlite/->SQLiteQuotaLimiter sys {:write-fn (:write-fn sys)
                                                        :limit 1
                                                        :window-ms (* 2 sqlite/minute-ms)
                                                        :prune-every nil})]
          (testing "Rejects while a prior bucket is still inside the custom window"
            (with-redefs [sqlite/now-ms (constantly base-ms)]
              (is (true? (core/admit? limiter org-id))))
            (with-redefs [sqlite/now-ms (constantly (+ base-ms sqlite/minute-ms))]
              (is (false? (core/admit? limiter org-id)))))

          (testing "Admits once the prior bucket falls outside the custom window"
            (with-redefs [sqlite/now-ms (constantly (+ base-ms (* 2 sqlite/minute-ms)))]
              (is (true? (core/admit? limiter org-id))))))))))

(deftest sqlite-quota-limiter-map-key-test
  (let [org-id (random-uuid)]
    (with-quiet-logging
      (with-test-db [sys "test-map-key-db" {:classpath-prefix "sturdy-throttle-migrations"}]
        (let [limiter (sqlite/->SQLiteQuotaLimiter sys {:write-fn (:write-fn sys)
                                                        :limit 5
                                                        :prune-every nil})]
          (testing "Different rate_key on same org gets own limit"
            (let [key-map {:org-id org-id :rate-key "other-endpoint"}]
              (dotimes [_ 5]
                (is (true? (core/admit? limiter key-map))))
              (is (false? (core/admit? limiter key-map)))))

          (testing "Map key without rate-key uses default rate key"
            (let [fresh-org (random-uuid)
                  key-map {:org-id fresh-org}]
              (dotimes [_ 5]
                (is (true? (core/admit? limiter key-map))))
              (is (false? (core/admit? limiter key-map))))))))))

(deftest sqlite-quota-limiter-validation-test
  (with-quiet-logging
    (with-test-db [sys "test-validation-db" {:classpath-prefix "sturdy-throttle-migrations"}]
      (let [limiter (sqlite/->SQLiteQuotaLimiter sys {:write-fn (:write-fn sys)
                                                      :limit 5
                                                      :prune-every nil})]
        (testing "Passing nil key throws ExceptionInfo"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rate limit key must contain a non-nil :org-id"
                                (core/admit? limiter nil))))

        (testing "Passing map with nil org-id throws ExceptionInfo"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rate limit key must contain a non-nil :org-id"
                                (core/admit? limiter {:org-id nil})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rate limit key must contain a non-nil :org-id"
                                (core/admit? limiter {:rate-key "some-key"}))))))))

(deftest sqlite-quota-limiter-error-test
  (let [org-id (random-uuid)]
    (with-quiet-logging
      (with-test-db [sys "test-error-db" {:classpath-prefix "sturdy-throttle-migrations"}]
        (let [limiter (sqlite/->SQLiteQuotaLimiter sys {:write-fn (:write-fn sys)
                                                        :limit 5
                                                        :prune-every nil})]
          (testing "Handles write errors gracefully"
            (let [config (.-config ^sturdy.throttle.sqlite.SQLiteQuotaLimiter limiter)
                  sys (.-sys ^sturdy.throttle.sqlite.SQLiteQuotaLimiter limiter)
                  error-limiter (sqlite/->SQLiteQuotaLimiter
                                 sys
                                 (assoc config :write-fn (fn [_] (throw (Exception. "Test Exception")))))]
              (is (false? (core/admit? error-limiter org-id))))))))))

(deftest sqlite-quota-limiter-prune-probability-test
  (testing "Pruning triggers via probability using mock system map"
    (let [async-called? (atom false)
          mock-sys {:write-async-fn (fn [_sql] (reset! async-called? true))}
          limiter (sqlite/->SQLiteQuotaLimiter mock-sys {:write-fn (constantly [{:next.jdbc/update-count 1}])
                                                         :limit 5
                                                         :prune-every 1})]
      (is (true? (core/admit? limiter (random-uuid))))
      (is (true? @async-called?)))))

(deftest sqlite-quota-limiter-prune-error-test
  (let [mock-sys {:write-async-fn (fn [_sql]
                                    (throw (Exception. "prune enqueue failed")))}]
    (testing "A prune enqueue failure does not change an admitted decision"
      (let [limiter (sqlite/->SQLiteQuotaLimiter
                     mock-sys
                     {:write-fn (constantly [{:next.jdbc/update-count 1}])
                      :limit 5
                      :prune-every 1})]
        (with-quiet-logging
          (is (true? (core/admit? limiter (random-uuid)))))))

    (testing "A prune enqueue failure does not change a rejected decision"
      (let [limiter (sqlite/->SQLiteQuotaLimiter
                     mock-sys
                     {:write-fn (constantly [{:next.jdbc/update-count 0}])
                      :limit 5
                      :prune-every 1})]
        (with-quiet-logging
          (is (false? (core/admit? limiter (random-uuid)))))))))

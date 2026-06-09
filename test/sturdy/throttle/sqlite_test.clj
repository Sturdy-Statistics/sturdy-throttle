(ns sturdy.throttle.sqlite-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sturdy.throttle.core :as core]
   [sturdy.throttle.sqlite :as sqlite]
   [sturdy.throttle.test-support :refer [with-quiet-logging]])
  (:import
   (java.io File)))

(defn delete-recursive [^File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)]
      (delete-recursive c)))
  (.delete f))

(deftest sqlite-quota-limiter-test
  (let [db-dir ".test/sqlite-limiter"
        db-name "testdb"
        org-id (random-uuid)]
    ;; Cleanup previous
    (delete-recursive (File. db-dir))
    (.mkdirs (File. db-dir))

    (with-quiet-logging
     (let [limiter (sqlite/make-quota-limiter {:db-dir db-dir
                                               :db-name db-name
                                               :limit 5
                                               :prune-every 10})]
       (try
         (testing "Allows up to limit"
           (dotimes [_ 5]
             (is (true? (core/admit? limiter org-id)))))

         (testing "Rejects over limit"
           (is (false? (core/admit? limiter org-id))))

         (testing "Different org gets its own limit"
           (let [other-org (random-uuid)]
             (is (true? (core/admit? limiter other-org)))))

         (finally
           (sqlite/close-limiter limiter)
           (delete-recursive (File. db-dir))))))))

(ns sturdy.throttle.sqlite
  (:require
   [ragtime.jdbc :as ragtime.jdbc]
   [ragtime.repl :as ragtime.repl]
   [sturdy.sqlite.core :refer [make-datasource]]
   [sturdy.sqlite.types :as types]
   [sturdy.throttle.core :refer [RateLimiter]]
   [taoensso.telemere :as t]))

(def ^:const minute-ms 60000)
(def ^:const hour-ms 3600000)

(defn now-ms [] (System/currentTimeMillis))

(defn bucket-start-ms ^long [^long now-ms]
  (* (quot now-ms minute-ms) minute-ms))

(defn oldest-live-bucket-ms ^long [^long now-ms]
  (- (bucket-start-ms now-ms) (* 59 minute-ms)))

(defn admit-sql
  "A single atomic statement that checks the rate limit and UPSERTs if allowed.
   Returns 1 updated row if admitted, or 0 if rejected by the WHERE clause."
  [org-id rate-key bucket-ms window-start limit]
  ["INSERT INTO api_minute_buckets (organization_id, rate_key, bucket_start_ms, request_count)
    SELECT ?, ?, ?, 1
    WHERE (SELECT COALESCE(SUM(request_count), 0)
           FROM api_minute_buckets
           WHERE organization_id = ? AND rate_key = ? AND bucket_start_ms >= ?) < ?
    ON CONFLICT (organization_id, rate_key, bucket_start_ms)
    DO UPDATE SET request_count = request_count + 1"
   org-id rate-key bucket-ms org-id rate-key window-start limit])

(defn prune-sql [now-ms]
  (let [cutoff (- (bucket-start-ms now-ms) hour-ms)]
    ["DELETE FROM api_minute_buckets WHERE bucket_start_ms < ?" cutoff]))

(deftype SQLiteQuotaLimiter [sys config]
  RateLimiter
  (admit? [_ k]
    (let [{:keys [org-id rate-key]} (if (map? k) k {:org-id k :rate-key "default"})
          {:keys [write-fn limit prune-every]} config
          t (now-ms)
          b-ms (bucket-start-ms t)
          w-ms (oldest-live-bucket-ms t)
          sql (admit-sql org-id rate-key b-ms w-ms limit)

          ;; Write synchronously through queue
          status (try
                   (let [res (write-fn sql)]
                     (if (pos? (-> res first :next.jdbc/update-count))
                       true
                       false))
                   (catch Exception e
                     (t/log! {:level :error} (str "sturdy-throttle SQLite error: " (.getMessage e)))
                     false))]

      ;; Async prune using probability
      (when (and prune-every (zero? (rand-int prune-every)))
        (let [write-async-fn (:write-async-fn sys)]
          (write-async-fn (prune-sql t))))

      status)))

(def b-opts (types/make-builder-opts {}))

(defn make-quota-limiter
  "Creates a SQLite-backed quota limiter.
   Initializes the database and runs migrations.
   `config` map requires:
   - :db-dir
   - :db-name
   - :limit (number of requests per hour)
   - :prune-every (approximate number of requests before running a background prune, e.g. 1000)"
  [{:keys [db-name db-dir limit prune-every batch-size profile-key]
    :or {batch-size 500
         profile-key :write-intensive
         prune-every 1000}}]
  (let [sys (make-datasource db-name db-dir profile-key
                                         {:batch-size batch-size
                                          :builder-opts b-opts})]
    ;; Run migrations on startup
    ((:migrate-fn sys) "sturdy-throttle-migrations")
    (->SQLiteQuotaLimiter sys
                          {:write-fn (:write-fn sys)
                           :limit limit
                           :prune-every prune-every})))

(defn close-limiter [limiter]
  (let [sys (.-sys ^SQLiteQuotaLimiter limiter)
        close-fn (:close-fn sys)]
    (when close-fn
      (close-fn))))

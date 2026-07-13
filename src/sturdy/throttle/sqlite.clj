(ns sturdy.throttle.sqlite
  (:require
   [clojure.string :as str]
   [sturdy.sqlite.core :refer [make-datasource]]
   [sturdy.sqlite.types :as types]
   [sturdy.throttle.core :refer [RateLimiter]]
   [taoensso.telemere :as t]
   [taoensso.truss :refer [have!]]))

(set! *warn-on-reflection* true)

(def ^:const minute-ms 60000)
(def ^:const hour-ms 3600000)

(defn now-ms [] (System/currentTimeMillis))

(defn- nonblank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-path? [value]
  (or (instance? java.io.File value)
      (instance? java.nio.file.Path value)
      (nonblank-string? value)))

(defn- nil-or-positive-integer? [value]
  (or (nil? value) (pos-int? value)))

(defn- validate-config!
  [{:keys [db-name db-dir limit window-ms prune-every batch-size profile-key]}]
  (have! nonblank-string? db-name :data {:option :db-name})
  (have! valid-path? db-dir :data {:option :db-dir})
  (have! pos-int? limit :data {:option :limit})
  (have! pos-int? window-ms :data {:option :window-ms})
  (have! nil-or-positive-integer? prune-every
         :data {:option :prune-every})
  (have! pos-int? batch-size :data {:option :batch-size})
  (have! keyword? profile-key :data {:option :profile-key}))

(defn bucket-start-ms ^long [^long now-ms]
  (* (quot now-ms minute-ms) minute-ms))

(defn window-bucket-count ^long [^long window-ms]
  (max 1 (long (Math/ceil (/ window-ms (double minute-ms))))))

(defn oldest-live-bucket-ms
  (^long [^long now-ms]
   (oldest-live-bucket-ms now-ms hour-ms))
  (^long [^long now-ms ^long window-ms]
   (- (bucket-start-ms now-ms)
      (* (dec (window-bucket-count window-ms)) minute-ms))))

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

(defn prune-sql
  ([now-ms]
   (prune-sql now-ms hour-ms))
  ([now-ms window-ms]
   (let [cutoff (oldest-live-bucket-ms now-ms window-ms)]
     ["DELETE FROM api_minute_buckets WHERE bucket_start_ms < ?" cutoff])))

(defn- normalize-key [k]
  (let [norm (if (map? k)
               (assoc k :rate-key (or (:rate-key k) "default"))
               {:org-id k :rate-key "default"})]
    (when-not (:org-id norm)
      (throw (ex-info "Rate limit key must contain a non-nil :org-id"
                      {:key k})))
    norm))

(defn- maybe-prune! [sys prune-every t window-ms]
  (when prune-every
    (try
      (when (zero? (rand-int prune-every))
        (let [write-async-fn (:write-async-fn sys)]
         (write-async-fn (prune-sql t window-ms))))
      (catch Exception e
        (t/log! {:level :error
                 :id ::sqlite-prune-error
                 :error e
                 :msg "sturdy-throttle SQLite prune scheduling failed"})))))

(deftype SQLiteQuotaLimiter [sys config]
  RateLimiter
  (admit? [_ k]
    (let [{:keys [org-id rate-key]} (normalize-key k)
          {:keys [write-fn limit prune-every]} config
          window-ms (or (:window-ms config) hour-ms)
          t (now-ms)
          b-ms (bucket-start-ms t)
          w-ms (oldest-live-bucket-ms t window-ms)
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

      ;; Best-effort async prune
      (maybe-prune! sys prune-every t window-ms)

      status)))

(def b-opts (types/make-builder-opts {}))

(defn make-quota-limiter
  "Creates a SQLite-backed quota limiter.
   Initializes the database and runs migrations.
   `config` map requires:
   - :db-dir
   - :db-name
   - :limit (number of requests per configured window)
   - :window-ms (window size in milliseconds; defaults to one hour)
   - :prune-every (approximate number of requests before running a background prune, e.g. 1000)"
  [{:keys [db-name db-dir limit window-ms prune-every batch-size profile-key]
    :or {batch-size 500
         profile-key :write-intensive
         window-ms hour-ms
         prune-every 1000}}]
  (validate-config! {:db-name db-name
                     :db-dir db-dir
                     :limit limit
                     :window-ms window-ms
                     :prune-every prune-every
                     :batch-size batch-size
                     :profile-key profile-key})
  (let [sys (make-datasource db-name db-dir profile-key
                             {:batch-size batch-size
                              :builder-opts b-opts})]
    ;; Run migrations on startup
    ((:migrate-fn sys) "sturdy-throttle-migrations")
    (->SQLiteQuotaLimiter sys
                          {:write-fn (:write-fn sys)
                           :limit limit
                           :window-ms window-ms
                           :prune-every prune-every})))

(defn close-limiter [limiter]
  (let [sys (.-sys ^SQLiteQuotaLimiter limiter)
        close-fn (:close-fn sys)]
    (when close-fn
      (close-fn))))

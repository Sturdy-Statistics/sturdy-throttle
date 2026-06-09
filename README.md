# sturdy-throttle

`sturdy-throttle` is a two-tiered rate limiting library for Clojure `ring` apps.
 It is designed to provide both DoS protection using a fast in-memory atom, and structured API quota enforcement using a SQLite table.

## Overview

1. **Pre-Authentication (IP Limiting)**: A fast, in-memory IP limiter using a fixed time-window.  Automatically sweeps and creates zero-garbage, returning empty `429 Too Many Requests` responses to save bandwidth against DoS attacks.
2. **Post-Authentication (Quota Enforcement)**: An asynchronous batched SQLite writer that manages rolling hourly quotas based on `organization_id` and an optional `rate_key`.  It automatically migrates the required schema on startup and prunes old buckets in the background.

## Reitit / Ring Application Integration

### 1. Server Startup

Initialize rate limiters during your application's startup sequence and store them in your system state.

```clojure
(ns my-app.server
  (:require [sturdy.throttle.memory :as memory]
            [sturdy.throttle.sqlite :as sqlite]))

(defn start-limiters [_config]
  ;; 1. The in-memory IP limiter
  ;; E.g., Max 50 requests per second per IP
  (let [ip-limiter (memory/make-ip-limiter {:limit-per-second 50
                                            :window-ms 1000})]

    ;; 2. The SQLite-backed quota limiter
    ;; E.g., Max 25,000 requests per hour per org
    (let [quota-limiter (sqlite/make-quota-limiter {:db-name "rate-limits"
                                                    :db-dir "/var/lib/my-app/limits"
                                                    :limit 25000
                                                    :prune-every 1000})]
      {:ip-limiter ip-limiter
       :quota-limiter quota-limiter})))
```

### 2. Adding Middleware to Reitit

You can wrap your routes using the provided middleware functions in
`sturdy.throttle`.
`wrap-ip-rate-limit` should be applied to the entire router (pre-auth), while `wrap-quota-rate-limit` should apply to to specific protected routes (post-auth).

```clojure
(ns my-app.routes
  (:require [reitit.ring :as ring]
            [sturdy.throttle :as throttle]))

(defn app-router [{:keys [ip-limiter quota-limiter]}]
  (ring/ring-handler
   (ring/router
    ["/api"
     ;; --- POST-AUTH QUOTA LIMITER ---
     ;; Apply quota limiting to all authenticated /api routes
     {:middleware [[throttle/wrap-quota-rate-limit
                    quota-limiter
                    (fn [req]
                      ;; Extract your org-id from the session/token.
                      ;; You can also include a :rate-key to target specific endpoints.
                      {:org-id (get-in req [:identity :org_id])
                       :rate-key "api-general"})
                    ;; Optional custom error response
                    {:status 429
                     :headers {"Content-Type" "application/json"}
                     :body "{\"error\": \"Hourly quota exceeded.\"}"}]]}

     ["/v1/resource" {:get handler}]]

    {:data
     ;; --- PRE-AUTH IP LIMITER ---
     ;; Apply IP rate limiting globally across the entire router
     {:middleware [[throttle/wrap-ip-rate-limit
                    ip-limiter
                    (fn [req]
                      ;; Extract IP from headers (behind proxy) or direct remote address
                      (or (get-in req [:headers "x-forwarded-for"])
                          (:remote-addr req)))]]}})))
```

### 3. Server Shutdown

Close the SQLite limiter in your server shutdown sequence.
This closes the SQLite connection cleanly.

```clojure
(defn stop-limiters [{:keys [quota-limiter]}]
  (when quota-limiter
    (sqlite/close-limiter quota-limiter)))
```

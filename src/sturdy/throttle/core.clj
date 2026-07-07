(ns sturdy.throttle.core)

(set! *warn-on-reflection* true)

(defprotocol RateLimiter
  (admit? [this key] "Returns true if the key is allowed under the rate limit, false otherwise."))

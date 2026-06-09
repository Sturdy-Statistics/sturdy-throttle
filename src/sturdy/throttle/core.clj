(ns sturdy.throttle.core)

(defprotocol RateLimiter
  (admit? [this key] "Returns true if the key is allowed under the rate limit, false otherwise."))

(ns sturdy.throttle
  (:require [sturdy.throttle.core :as core]))

(defn wrap-ip-rate-limit
  "Ring middleware that uses an in-memory IP rate limiter.
   Blocks offenders with a minimal 429 response to save bandwidth."
  [handler limiter extract-ip]
  (fn [request]
    (let [ip (extract-ip request)]
      (if (and ip (not (core/admit? limiter ip)))
        {:status 429
         :headers {}
         :body ""}
        (handler request)))))

(defn wrap-quota-rate-limit
  "Ring middleware that uses the SQLite quota limiter.
   Provides a more helpful error message when the quota is exceeded."
  [handler limiter extract-key error-response]
  (fn [request]
    (let [k (extract-key request)]
      (if (and k (not (core/admit? limiter k)))
        (or error-response
            {:status 429
             :headers {"Content-Type" "text/plain"}
             :body "Rate limit exceeded. Please try again later."})
        (handler request)))))

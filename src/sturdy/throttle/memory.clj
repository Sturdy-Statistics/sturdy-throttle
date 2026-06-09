(ns sturdy.throttle.memory
  (:require
   [sturdy.throttle.core :refer [RateLimiter]]))

(deftype IPAtomLimiter [state limit window-ms]
  RateLimiter
  (admit? [_ ip]
    (let [ret (swap! state
                     (fn [{:keys [expires-at counts] :as s}]
                       (let [now (System/currentTimeMillis)]
                         (if (>= now expires-at)
                           ;; window expired, reset map
                           {:expires-at (+ now window-ms)
                            :counts {ip 1}}
                           ;; within window, increment count
                           (let [current-count (get counts ip 0)]
                             (assoc s :counts (assoc counts ip (inc current-count))))))))]
      (<= (get-in ret [:counts ip]) limit))))

(defn make-ip-limiter
  [{:keys [limit-per-second window-ms]
    :or {limit-per-second 50
         window-ms 1000}}]
  (let [now (System/currentTimeMillis)]
    (->IPAtomLimiter (atom {:expires-at (+ now window-ms) :counts {}})
                     limit-per-second
                     window-ms)))

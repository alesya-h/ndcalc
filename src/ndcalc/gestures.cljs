(ns ndcalc.gestures
  (:require [ndcalc.state :as s]))

(defn metrics [touches]
  (let [a (aget touches 0) b (or (aget touches 1) a)]
    {:count (.-length touches) :x (/ (+ (.-clientX a) (.-clientX b)) 2)
     :y (/ (+ (.-clientY a) (.-clientY b)) 2)
     :distance (js/Math.hypot (- (.-clientX b) (.-clientX a)) (- (.-clientY b) (.-clientY a)))}))

(defn install! [el]
  (let [touch (atom nil) last-tap (atom nil) remainder (atom [0 0])
        plane? #(.contains (.-classList el) "grid-container")
        pan (fn [dx dy]
              (let [before [(.-scrollLeft el) (.-scrollTop el)]]
                (set! (.-scrollLeft el) (+ (first before) dx))
                (set! (.-scrollTop el) (+ (second before) dy))
                (when (plane?)
                  (let [[rx ry] @remainder
                        scale (/ (s/view-zoom) 100)
                        cell (.querySelector el "[data-coord]")
                        width (if cell (.-width (.getBoundingClientRect cell)) (* scale 70))
                        px (+ rx (- dx (- (.-scrollLeft el) (first before))))
                        py (+ ry (- dy (- (.-scrollTop el) (second before))))
                        x (js/Math.trunc (/ px (max 1 width))) y (js/Math.trunc (/ py (* scale 35)))]
                    (reset! remainder [(- px (* x width)) (- py (* y scale 35))])
                    (when (or (not (zero? x)) (not (zero? y))) (s/pan-plane! x y))))))
        wheel (fn [event]
                (if (s/preview-wheel! event)
                  (.stopImmediatePropagation event)
                  (when (and (plane?) (not (.-altKey event)) (not (.-ctrlKey event)) (not (.-metaKey event)))
                    (s/consume! event)
                    (let [factor (case (.-deltaMode event) 1 16 2 240 1)
                          x (* factor (.-deltaX event)) y (* factor (.-deltaY event))]
                      (if (and (.-shiftKey event) (zero? x)) (pan y 0) (pan x y))))))
        start (fn [event]
                (s/consume! event)
                (let [m (metrics (.-touches event))]
                  (reset! touch {:previous m :base m :zoom (s/view-zoom) :moved (> (:count m) 1) :target (.-target event)})))
        move (fn [event]
               (when-let [gesture @touch]
                 (s/consume! event)
                 (let [m (metrics (.-touches event)) p (:previous gesture)]
                   (if (not= (:count m) (:count p))
                     (reset! touch (assoc gesture :previous m :base m :zoom (s/view-zoom) :moved true))
                     (do
                       (when (and (>= (:count m) 2) (pos? (:distance (:base gesture))))
                         (s/set-view-zoom! (* (:zoom gesture) (/ (:distance m) (:distance (:base gesture))))))
                       (pan (- (:x p) (:x m)) (- (:y p) (:y m)))
                       (swap! touch assoc :previous m :moved (or (:moved gesture) (>= (:count m) 2)
                                                               (> (js/Math.hypot (- (:x m) (:x (:base gesture)))
                                                                                    (- (:y m) (:y (:base gesture)))) 6))))))))
        finish (fn [event]
                 (when-let [gesture @touch]
                   (s/consume! event)
                   (if (pos? (.-length (.-touches event)))
                     (let [m (metrics (.-touches event))] (reset! touch (assoc gesture :previous m :base m :zoom (s/view-zoom) :moved true)))
                     (do
                       (when-not (:moved gesture)
                         (when-let [target (.closest (:target gesture) "[data-coord],button[data-hyperplane]")]
                           (let [now (.now js/Date)]
                             (.click target)
                             (when (and (identical? target (:target @last-tap)) (< (- now (:time @last-tap)) 350))
                               (.dispatchEvent target (js/MouseEvent. "dblclick" #js {:bubbles true :cancelable true})))
                             (reset! last-tap {:target target :time now}))))
                       (reset! touch nil)))))
        cancel (fn [_] (reset! touch nil))
        listeners [["wheel" wheel] ["touchstart" start] ["touchmove" move] ["touchend" finish] ["touchcancel" cancel]]]
    (doseq [[name handler] listeners] (.addEventListener el name handler #js {:capture true :passive false}))
    (fn [] (doseq [[name handler] listeners] (.removeEventListener el name handler true)))))

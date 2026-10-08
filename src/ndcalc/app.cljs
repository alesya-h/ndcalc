(ns ndcalc.app
  (:require [reagent.dom.client :as dom]
            [ndcalc.state :as state]
            [ndcalc.ui :as ui]))

(defonce root (atom nil))
(defn ^:dev/after-load mount! []
  (when-not @root (reset! root (dom/create-root (.getElementById js/document "app"))))
  (dom/render @root [ui/app-view]))

(defn init []
  (state/install-system-theme!)
  (mount!)
  (.addEventListener js/document "keydown" state/keydown!)
  (state/init!))

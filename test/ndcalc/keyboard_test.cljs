(ns ndcalc.keyboard-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [ndcalc.state :as s]
            [ndcalc.demo :as demo]
            [ndcalc.engine :as e]))

(use-fixtures :each
  {:before #(do (remove-watch s/app :persist)
                (reset! s/app {:route :editor :doc (demo/blank-document "keys" 8) :view :plane
                               :cube-axes [1 2 3] :hyper-axes [1 2 3 4]
                               :mode :normal :help false :undo [] :redo []
                               :viewport [-1 -1] :grid-size [8 12]}))
   :after #(when @s/toast-timer (js/clearTimeout @s/toast-timer))})

(defn key! [key & [mods]]
  (s/keydown! #js {:key key :ctrlKey (boolean (:ctrl mods)) :altKey (boolean (:alt mods))
                  :shiftKey (boolean (:shift mods)) :metaKey false
                  :target #js {:tagName (or (:target mods) "DIV")}
                  :preventDefault (fn [])}))

(deftest panels-help-and-dvorak-friendly-navigation
  (key! "c") (is (= :css (:panel @s/app)))
  (key! "r") (is (= :rules (:panel @s/app)))
  (key! "n") (is (= :named (:panel @s/app)))
  (is (nil? (:editor @s/app)))
  (key! "h") (is (:help @s/app))
  (doseq [key ["j" "k" "l"]] (key! key))
  (is (= (vec (repeat 8 0)) (s/coord)))
  (key! "h") (is (false? (:help @s/app)))
  (key! "N" {:shift true}) (is (get-in @s/app [:editor :new-name])))

(deftest home-end-use-this-populated-row-only
  (s/change! #(reduce (fn [doc c] (e/put-cell doc c {:kind "value" :source "1"})) %
                      [[-5 2 3] [7 2 3] [-100 3 3] [100 2 4]]))
  (s/select! [1 2 3] false)
  (key! "Home") (is (= [-5 2 3 0 0 0 0 0] (s/coord)))
  (key! "End") (is (= [7 2 3 0 0 0 0 0] (s/coord)))
  (key! "Home" {:shift true}) (is (= 13 (count (s/selected-coords))))
  (key! "Escape") (key! "t")
  (let [c (s/coord)] (key! "End") (is (= c (s/coord)))))

(deftest page-navigation-follows-most-recent-expulsion
  (s/switch! 3)
  (is (= [2 3 1 4 5 6 7 8] (s/navigation-axes)))
  (key! "PageUp") (is (= [1 0 0 0 0 0 0 0] (s/coord)))
  (s/switch! 4)
  (is (= [3 4 2 1 5 6 7 8] (s/navigation-axes)))
  (key! "PageDown" {:shift true})
  (is (= [1 -1 0 0 0 0 0 0] (s/coord)))
  (is (= 2 (count (s/selected-coords))))
  (key! "t")
  (is (= [3 4 2] (s/view-axes)))
  (key! "t")
  (is (= 2 (nth (s/navigation-axes) 2)))
  (is (= (get-in @s/app [:doc :view :expelled])
         (get-in (e/json->document (e/document->json (:doc @s/app))) [:view :expelled]))))

(deftest modifier-arrows-reach-eight-dimensions-with-selection
  (doseq [[key mods] [["ArrowUp" {:ctrl true}] ["ArrowRight" {:ctrl true}]
                      ["ArrowUp" {:alt true}] ["ArrowRight" {:alt true}]
                      ["ArrowUp" {:ctrl true :alt true}] ["ArrowRight" {:ctrl true :alt true}]
                      ["ArrowRight" {}] ["ArrowDown" {}]]]
    (key! key (assoc mods :shift true)))
  (is (= (vec (repeat 8 1)) (s/coord)))
  (is (= (vec (repeat 8 0)) (:anchor @s/app)))
  (is (= 256 (count (s/selected-coords))))
  (key! "PageDown" {:shift true})
  (is (= 128 (count (s/selected-coords))))
  (key! "Escape")
  (doseq [[key mods] [["ArrowDown" {:ctrl true}] ["ArrowLeft" {:ctrl true}]
                      ["ArrowDown" {:alt true}] ["ArrowLeft" {:alt true}]
                      ["ArrowDown" {:ctrl true :alt true}] ["ArrowLeft" {:ctrl true :alt true}]]]
    (key! key mods))
  (is (= [1 1 -1 0 0 0 0 0] (s/coord))))

(deftest permutations-cover-all-axis-orders-without-moving-selection
  (s/toggle-visual!)
  (key! "T" {:shift true}) (is (= [2 1] (s/view-axes)))
  (key! "T" {:shift true}) (is (= [1 2] (s/view-axes)))
  (key! "t")
  (let [orders (for [_ (range 6)] (do (key! "T" {:shift true}) (s/view-axes)))]
    (is (= 6 (count (set orders)))))
  (is (= [1 2 3] (s/view-axes)))
  (key! "t" {:ctrl true}) (is (= "slices" (:layout (s/cube-options))))
  (key! "t" {:ctrl true}) (is (= "stack" (:layout (s/cube-options))))
  (key! "T" {:ctrl true :shift true})
  (is (= :hypercube (:view @s/app)))
  (let [orders (for [_ (range 24)] (do (key! "T" {:shift true}) (s/view-axes)))]
    (is (= 24 (count (set orders)))))
  (is (= [1 2 3 4] (s/view-axes)))
  (is (= :visual (:mode @s/app)))
  (is (= (vec (repeat 8 0)) (:anchor @s/app))))

(deftest four-dimensional-fill-clear-and-undo
  (key! "T" {:ctrl true :shift true})
  (s/toggle-visual!)
  (s/select! [1 1 1 1] false)
  (s/open-editor! "formula")
  (swap! s/app assoc-in [:editor :source] "(a,b,c,d) => [a,b,c,d]")
  (s/save-editor!)
  (is (= 16 (count (get-in @s/app [:doc :cells]))))
  (is (= [2 2 2 2] (:shape (do (s/fit-hyper!) (s/hyper-window)))))
  (s/undo! false) (is (empty? (get-in @s/app [:doc :cells])))
  (s/undo! true) (is (= 16 (:total (s/hyper-window))))
  (s/switch! 5) (is (= [2 3 4 5] (s/view-axes)))
  (is (= :hypercube (:view @s/app))))

(deftest typing-does-not-trigger-application-keys
  (doseq [key ["h" "c" "r" "N" "PageUp" "T"]] (key! key {:target "TEXTAREA"}))
  (is (false? (:help @s/app)))
  (is (nil? (:editor @s/app)))
  (is (= (vec (repeat 8 0)) (s/coord)))
  (is (= [1 2] (s/view-axes))))

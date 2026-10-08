(ns ndcalc.state-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [ndcalc.engine :as e]
            [ndcalc.demo :as demo]
            [ndcalc.state :as s]))

(use-fixtures :each
  {:before #(do (remove-watch s/app :persist)
                (reset! s/app {:route :editor :doc (demo/blank-document "test" 5) :view :plane
                               :mode :normal :undo [] :redo [] :viewport [-1 -1] :grid-size [8 12]}))
   :after #(when @s/toast-timer (js/clearTimeout @s/toast-timer))})

(deftest navigation-preserves-inactive-dimensions
  (s/select! [-1 -2 3 4 5] false)
  (s/move! 1 -1 false)
  (is (= [0 -3 3 4 5] (s/coord)))
  (s/switch! 3)
  (s/move! 1 1 false)
  (is (= [0 -2 4 4 5] (s/coord)))
  (s/switch! 3)
  (is (= [3 0] (s/mapping)))
  (s/move! 1 10 false)
  (is (= [0 -2 5 4 5] (s/coord))))

(deftest safe-coordinate-boundaries
  (s/jump! 0 js/Number.MAX_SAFE_INTEGER)
  (is (= js/Number.MAX_SAFE_INTEGER (first (s/coord))))
  (is (<= (+ (first (:viewport @s/app)) 7) js/Number.MAX_SAFE_INTEGER))
  (s/move! 1 0 false)
  (is (= js/Number.MAX_SAFE_INTEGER (first (s/coord))))
  (s/jump! 0 (- js/Number.MAX_SAFE_INTEGER))
  (is (= (- js/Number.MAX_SAFE_INTEGER) (first (:viewport @s/app))))
  (s/move! -1 0 false)
  (is (= (- js/Number.MAX_SAFE_INTEGER) (first (s/coord)))))

(deftest visual-selection-and-function-fill
  (s/select! [-2 -3 1 0 0] false)
  (s/toggle-visual!)
  (s/move! 1 1 false)
  (is (= 4 (count (s/selected-coords))))
  (s/open-editor! "formula")
  (swap! s/app assoc-in [:editor :source] "(a,b,...rest) => a+b+rest[0]")
  (s/save-editor!)
  (is (= :normal (:mode @s/app)))
  (is (= 4 (count (get-in @s/app [:doc :cells]))))
  (is (= -4 (:value ((:evaluate (s/runtime)) [-2 -3 1 0 0])))))

(deftest null-and-named-selections
  (s/select! ["plain"] false)
  (s/toggle-visual!)
  (is (nil? (:anchor @s/app)))
  (is (= [["plain"]] (s/selected-coords)))
  (s/move! 1 0 false)
  (is (= [0 0 0 0 0] (s/coord)))
  (s/set-mapping! 0 0)
  (s/set-mapping! 1 0)
  (s/move! 100 100 true)
  (is (= [[0 0 0 0 0]] (s/selected-coords)))
  (s/switch! 1)
  (is (nil? (:anchor @s/app))))

(deftest axis-clear-stays-in-the-current-slice
  (s/change! #(-> %
                  (e/put-cell [0 0] {:kind "value" :source "1"})
                  (e/put-cell [0 1] {:kind "value" :source "2"})
                  (e/put-cell [1 1] {:kind "value" :source "3"})
                  (e/put-cell [0 1 1] {:kind "value" :source "4"})))
  (s/clear-axis! 0)
  (is (nil? (e/cell-at (:doc @s/app) [0 0])))
  (is (nil? (e/cell-at (:doc @s/app) [0 1])))
  (is (= "3" (:source (e/cell-at (:doc @s/app) [1 1]))))
  (is (= "4" (:source (e/cell-at (:doc @s/app) [0 1 1]))))
  (s/undo! false)
  (is (= 4 (count (get-in @s/app [:doc :cells])))))

(deftest cube-axes-and-navigation-stay-in-sync
  (swap! s/app assoc :cube-axes [1 2 3])
  (s/toggle-3d!)
  (s/switch! 3)
  (is (= [2 3 1] (:cube-axes @s/app)))
  (s/move! 1 1 false)
  (is (= [0 1 1 0 0] (s/coord)))
  (s/set-cube-axis! 2 2)
  (is (= [1 3 2] (:cube-axes @s/app)))
  (is (= [1 3] (s/mapping)))
  (s/switch! 3)
  (is (= :plane (:view @s/app)))
  (is (= [3 0] (s/mapping))))

(deftest dimension-matched-formula-templates
  (doseq [[n expected] [[0 "(...rest) => "] [1 "(a,...rest) => "]
                        [2 "(a,b,...rest) => "] [3 "(a,b,c,...rest) => "]
                        [5 "(a,b,c,d,e,...rest) => "]]]
    (is (= expected (s/formula-template n false))))
  (is (= "(name,...rest) => " (s/formula-template 5 true)))
  (is (fn? (e/compile-expression (str (s/formula-template 32 false) "0") nil)))
  (s/open-editor! "formula")
  (is (= "(a,b,c,d,e,...rest) => " (get-in @s/app [:editor :source])))
  (is (true? (get-in @s/app [:editor :focus-source])))
  (swap! s/app assoc :editor nil)
  (s/open-editor! nil)
  (s/set-editor-kind! "formula")
  (is (= "(a,b,c,d,e,...rest) => " (get-in @s/app [:editor :source])))
  (swap! s/app assoc-in [:editor :source] "x => 42")
  (s/set-editor-kind! "value")
  (s/set-editor-kind! "formula")
  (is (= "x => 42" (get-in @s/app [:editor :source])))
  (s/new-named!)
  (s/set-editor-kind! "formula")
  (is (= "(name,...rest) => " (get-in @s/app [:editor :source]))))

(deftest existing-sources-are-not-replaced-by-templates
  (let [source "(a,b,...rest) => 42"]
    (s/change! #(e/put-cell % [] {:kind "formula" :source source}))
    (s/open-editor! nil)
    (is (= source (get-in @s/app [:editor :source]))))
  (s/change! #(e/put-cell % [] {:kind "value" :source "123"}))
  (s/open-editor! "formula")
  (is (= "123" (get-in @s/app [:editor :source]))))

(deftest readonly-means-no-content-changes
  (s/change! #(e/put-cell % [] {:kind "value" :source "5"}))
  (swap! s/app assoc :view :cube)
  (let [before (:doc @s/app)]
    (s/clear!) (s/undo! false) (s/clear-axis! 0) (s/open-editor! "value")
    (is (= before (:doc @s/app)))
    (is (nil? (:editor @s/app)))))

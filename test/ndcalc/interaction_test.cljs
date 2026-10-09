(ns ndcalc.interaction-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [ndcalc.engine :as e] [ndcalc.demo :as demo] [ndcalc.state :as s]
            [ndcalc.layout :as layout] [ndcalc.gestures :as gestures]))

(use-fixtures :each
  {:before #(do (remove-watch s/app :persist) (s/open-document! (demo/blank-document "interaction" 4)))
   :after #(when @s/toast-timer (js/clearTimeout @s/toast-timer))})
(defn value [source] {:kind "value" :source source})
(defn result [doc c] ((:evaluate (e/make-runtime doc)) c))

(deftest text-is-only-a-string-literal-presentation
  (doseq [text ["" "hello" "42" "=> $$(1,0)" "a\"b'c`d\\e\nf\t☺" "${globalThis.notExecuted=1}"]]
    (s/open-editor! "text") (swap! s/app assoc-in [:editor :source] text) (s/save-editor!)
    (let [cell (e/cell-at (:doc @s/app) [0 0])]
      (is (= "value" (:kind cell))) (is (= (js/JSON.stringify text) (:source cell)))
      (is (= text (:value (result (:doc @s/app) [0 0]))))
      (s/open-editor! nil)
      (is (= "text" (get-in @s/app [:editor :kind])))
      (is (= text (get-in @s/app [:editor :source])))))
  (s/set-editor-kind! "value")
  (is (= (js/JSON.stringify "${globalThis.notExecuted=1}") (get-in @s/app [:editor :source])))
  (s/set-editor-kind! "text")
  (is (= "${globalThis.notExecuted=1}" (get-in @s/app [:editor :source]))))

(deftest automatic-text-mode-does-not-evaluate-expressions
  (doseq [source ["'hello'" "\"hello\"" "`hello`" "'a\\n\\\"b'"]]
    (is (string? (:text (e/literal-string (value source))))))
  (doseq [source ["'hello'+'world'" "`hello ${globalThis.textProbe=1}`" "(()=>{globalThis.textProbe=1;return 'hello'})()"
                  "'hello'; globalThis.textProbe=1" "\"bad\nquote\"" "'unterminated" "(()=> 'hello')"]]
    (is (nil? (e/literal-string (value source)))))
  (is (undefined? (aget js/globalThis "textProbe")))
  (is (nil? (e/literal-string {:kind "formula" :source "()=> 'hello'"}))))

(deftest h-cursors-restrict-motion-and-operate-on-hypercells
  (s/select! [3 4 5 6] false) (s/cycle-cursor!)
  (is (= {:hyperplane [1 3]} (s/coord)))
  (s/move! 0 1 true) (is (nil? (:anchor @s/app)))
  (s/move-slot! 3 1 true) (is (nil? (:anchor @s/app)))
  (s/move! -2 0 true)
  (is (= [{:hyperplane [1 1]} {:hyperplane [1 2]} {:hyperplane [1 3]}] (s/selected-coords)))
  (is (= [1 4 5 6] (s/numeric-coord)))
  (s/open-editor! "text") (swap! s/app assoc-in [:editor :source] "header") (s/save-editor!)
  (is (= 3 (count (get-in @s/app [:doc :hyperplanes]))))
  (is (empty? (get-in @s/app [:doc :cells])))
  (s/toggle-visual!) (s/move! 2 0 false) (s/yank!)
  (s/cycle-cursor!) (is (= {:hyperplane [2 4]} (s/coord)))
  (s/move! 1 0 true) (is (nil? (:anchor @s/app)))
  (s/paste!)
  (doseq [c [4 5 6]] (is (= "header" (:value (result (:doc @s/app) {:hyperplane [2 c]})))))
  (s/toggle-visual!) (s/move! 0 2 false) (s/clear!)
  (doseq [c [4 5 6]] (is (nil? (e/cell-at (:doc @s/app) {:hyperplane [2 c]}))))
  (s/cycle-cursor!) (is (= [3 6 5 6] (s/coord)))
  (s/paste!)
  (doseq [c [3 4 5]] (is (= "header" (:value (result (:doc @s/app) [c 6 5 6]))))))

(deftest shift-header-picking-anchors-at-the-previous-coordinate
  (s/focus-hyperplane! 1 3 :hyperrow true)
  (is (= 4 (count (s/selected-coords))))
  (is (= {:hyperplane [1 0]} (:anchor @s/app)))
  (s/focus-hyperplane! 1 3 :hyperrow false)
  (is (= 4 (count (s/selected-coords)))))

(deftest hyperplane-blocks-are-safe-and-dimension-specific
  (is (= 3 (e/block-size {:hyperplane [2 -1]} {:hyperplane [2 1]})))
  (is (e/in-block? {:hyperplane [2 -1]} {:hyperplane [2 1]} {:hyperplane [2 0]}))
  (is (not (e/in-block? {:hyperplane [2 -1]} {:hyperplane [2 1]} {:hyperplane [1 0]})))
  (is (thrown? js/Error (e/block-coords {:hyperplane [2 0]} {:hyperplane [3 1]})))
  (is (thrown? js/Error (e/block-coords {:hyperplane [2 0]} {:hyperplane [2 10000]})))
  (s/set-mapping! 0 0) (s/cycle-cursor!) (s/move! 1 0 true)
  (is (= [{:hyperplane [0 0]}] (s/selected-coords)))
  (is (nil? (:anchor @s/app))))

(deftest widths-are-independent-source-preserving-metadata
  (let [doc (-> (demo/blank-document "width" 3)
                (e/put-cell [0 0] (value "'short'"))
                (e/put-cell [0 100] (value "'A considerably longer string'"))
                (e/set-cell-width [1 0] 275)
                (e/set-cell-width {:hyperplane [2 0]} 120)
                (e/set-cell-width ["__proto__"] 350))
        runtime (e/make-runtime doc) widths (layout/column-widths doc runtime [0 0 0] [1 2] [0 1 2] true 35)
        copy (e/json->document (e/document->json doc))]
    (is (= (:cell-widths doc) (:cell-widths copy)))
    (is (= (:cells doc) (:cells copy)))
    (is (> (first widths) 200)) (is (= 275 (second widths))) (is (= 35 (nth widths 2)))
    (is (= 35 (layout/natural-width "" false 35)))
    (is (= 400 (layout/natural-width (apply str (repeat 1000 "a")) false 35)))
    (is (= {:start [0 0 0] :end [0 100 0]} (e/active-bounds doc)))
    (is (nil? (e/cell-width (e/set-cell-width doc [1 0] nil) [1 0])))
    (doseq [width [34 2001 99.5 "100" js/NaN]] (is (thrown? js/Error (e/set-cell-width doc [0] width))))
    (is (thrown? js/Error (e/json->document (e/document->json (assoc doc :cell-widths {"[0,0,0]" -1})))))))

(deftest clearing-cells-removes-widths-including-empty-headers-and-names
  (doseq [target [[0 0] [2 0] {:hyperplane [1 3]} ["__proto__"]]]
    (let [doc (cond-> (demo/blank-document "clear widths" 4)
                (not= target [2 0]) (e/put-cell target (value "42")))
          doc (e/set-cell-width doc target 275)]
      (s/open-document! doc)
      (cond (e/hyperplane? target) (s/focus-hyperplane! 1 3 :hyperrow false)
            :else (s/pick-cell! target false))
      (s/clear!)
      (is (nil? (e/cell-at (:doc @s/app) target)))
      (is (nil? (e/cell-width (:doc @s/app) target)))
      (s/undo! false)
      (is (= 275 (e/cell-width (:doc @s/app) target)))
      (is (= (e/cell-at doc target) (e/cell-at (:doc @s/app) target))))))

(deftest clearing-blocks-and-axes-removes-only-matching-widths
  (let [doc (reduce #(e/set-cell-width %1 %2 180)
                    (e/put-cell (demo/blank-document "clear axis" 4) [0 0] (value "42"))
                    [[0 0] [0 3] [1 0] [0 0 1] {:hyperplane [1 0]} ["named"]])]
    (s/open-document! doc) (s/clear-axis! 0)
    (doseq [c [[0 0] [0 3]]]
      (is (nil? (e/cell-width (:doc @s/app) c))))
    (doseq [c [[1 0] [0 0 1] {:hyperplane [1 0]} ["named"]]]
      (is (= 180 (e/cell-width (:doc @s/app) c))))
    (s/undo! false) (is (= (:cell-widths doc) (get-in @s/app [:doc :cell-widths])))
    (s/toggle-visual!) (s/move! 1 0 false) (s/clear!)
    (doseq [c [[0 0] [1 0]]] (is (nil? (e/cell-width (:doc @s/app) c))))
    (is (= 180 (e/cell-width (:doc @s/app) [0 3])))))

(deftest resetting-width-only-empty-cells-does-not-create-values
  (s/set-cell-width! [0 0] 275) (s/open-editor! nil)
  (is (= 275 (get-in @s/app [:editor :width])))
  (swap! s/app assoc-in [:editor :width] "") (s/save-editor!)
  (is (nil? (:editor @s/app)))
  (is (empty? (get-in @s/app [:doc :cells])))
  (is (nil? (e/cell-width (:doc @s/app) [0 0])))
  (s/undo! false) (is (= 275 (e/cell-width (:doc @s/app) [0 0])))
  (s/open-editor! nil) (swap! s/app assoc-in [:editor :width] "125") (s/save-editor!)
  (is (= 125 (e/cell-width (:doc @s/app) [0 0])))
  (is (nil? (e/cell-at (:doc @s/app) [0 0]))))

(deftest touch-metrics-and-bounded-zoom
  (is (= {:count 1 :x 10 :y 20 :distance 0} (gestures/metrics #js [#js {:clientX 10 :clientY 20}])))
  (is (= {:count 2 :x 50 :y 20 :distance 100} (gestures/metrics #js [#js {:clientX 0 :clientY 20} #js {:clientX 100 :clientY 20}])))
  (s/set-view-zoom! 700) (is (= 200 (s/view-zoom)))
  (s/set-view-zoom! 1) (is (= 25 (s/view-zoom)))
  (s/set-view! :cube) (s/set-view-zoom! 160) (is (= 160 (s/view-zoom)))
  (s/set-view! :hypercube) (s/set-view-zoom! 130) (is (= 130 (s/view-zoom))))

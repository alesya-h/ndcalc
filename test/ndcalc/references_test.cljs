(ns ndcalc.references-test
  (:require [cljs.test :refer-macros [deftest is]]
            [ndcalc.engine :as e]
            [ndcalc.demo :as demo]
            [ndcalc.state :as s]))

(defn value [source] {:kind "value" :source source})
(defn formula [source] {:kind "formula" :source source})
(defn base []
  (-> (demo/blank-document "references" 3)
      (e/set-aliases {1 "date" 2 "department" 3 "channel"})
      (e/put-cell [4 2 0] (value "10"))
      (e/put-cell [5 2 15] (value "20"))
      (e/put-cell {:hyperplane [1 42]} (value "'Future'"))
      (e/put-cell {:hyperplane [2 2]} (value "'Sales'"))))
(defn result [doc coord] ((:evaluate (e/make-runtime doc)) coord))

(deftest all-requested-reference-forms
  (doseq [[source expected] [["=> $(_.offset('date', -1)) + 1" 11]
                             ["=> _.offset(3, 15).value() + 1" 21]
                             ["=> $$('department', _)" "Sales"]
                             ["=> _.value('department')" "Sales"]
                             ["=> $$['department']" "Sales"]
                             ["=> $$.department" "Sales"]
                             ["=> $$(1,42)" "Future"]
                             ["=> $$('date',42)" "Future"]
                             ["=> $$('department')" "Sales"]
                             ["=> $([4,2,0])" 10]]]
    (is (= {:value expected} (result (e/put-cell (base) [5 2 0] (formula source)) [5 2 0])) source)))

(deftest coordinate-objects-are-immutable-and-bind-to-each-cell
  (let [doc (-> (base)
                (e/put-cell [5 2] (formula "() => {const prev=_.offset('date',-1); return [_.coordinate('date'),prev.coordinate('date'),Object.isFrozen(_),Object.isFrozen(_.coords)];}"))
                (e/put-cell [6 2] (formula "=> $(5,2)[0] + _.coordinate('date')")))]
    (is (= [5 4 true true] (js->clj (:value (result doc [5 2])))))
    (is (= 11 (:value (result doc [6 2])))))
  (doseq [source ["=> _.offset(1,Number.MAX_SAFE_INTEGER).value()" "=> _.offset('missing',1).value()" "=> _.offset(1,0.5).value()"]]
    (is (:error (result (e/put-cell (base) [5 2] (formula source)) [5 2]))))
  (is (:error (result (e/put-cell (base) ["named"] (formula "=> _.offset(1,1).value()")) ["named"]))))

(deftest hyperplanes-are-global-sparse-cells-with-their-own-dependencies
  (let [doc (-> (base)
                (e/put-cell {:hyperplane [1 -1]} (value "40"))
                (e/put-cell {:hyperplane [1 0]} (formula "=> _.offset('date', -1).value() + 2"))
                (e/put-cell [0 2] (formula "=> _.value('date')")))
        runtime (e/make-runtime doc)]
    (is (= 42 (:value ((:evaluate runtime) [0 2]))))
    (is (contains? (get @(:dependencies runtime) "[0,2,0]") (e/coord-key {:hyperplane [1 0]})))
    (is (= {:start [0 2 0] :end [5 2 15]} (e/active-bounds doc)))
    (is (= js/undefined (:value ((:evaluate runtime) {:hyperplane [2 99]}))))
    (is (= 42 (:value (result (e/put-cell doc [0 2 15] (formula "=> $$.date")) [0 2 15])))))
  (let [doc (-> (base)
                (e/put-cell {:hyperplane [2 2]} (value "x => x*2"))
                (e/put-cell [5 2] (formula "=> $$.department(21)")))]
    (is (= 42 (:value (result doc [5 2])))))
  (let [doc (-> (base)
                (e/put-cell [5 2] (formula "=> $$.department"))
                (e/put-cell {:hyperplane [2 2]} (formula "=> $(5,2)")))]
    (is (re-find #"Circular reference" (:error (result doc [5 2])))))
  (is (:error (result (e/put-cell (base) [5 2] (formula "=> _.value()")) [5 2]))))

(deftest aliases-and-hyperplanes-persist-and-validate
  (let [doc (base) copy (e/json->document (e/document->json doc))]
    (is (= (:aliases doc) (:aliases copy)))
    (is (= (:hyperplanes doc) (:hyperplanes copy)))
    (is (= "Sales" (:value (result (e/put-cell copy [5 2] (formula "=> $$.department")) [5 2]))))
    (is (thrown? js/Error (e/resize-dimensions doc 1))))
  (doseq [aliases [{1 "a" 2 "a"} {4 "a"} {1 ""} {"1" "a"}]]
    (is (thrown? js/Error (e/set-aliases (base) aliases))))
  (doseq [target [{:hyperplane [4 0]} {:hyperplane [0 1]} {:hyperplane [1 0.5]} {:hyperplane [1 0 0]}]]
    (is (thrown? js/Error (e/put-cell (base) target (value "1")))))
  (doseq [alias ["name" "constructor" "__proto__" "caller" "length" "two words"]]
    (let [doc (-> (base) (e/set-aliases {2 alias})
                  (e/put-cell [5 2] (formula (str "=> $$[" (js/JSON.stringify alias) "]"))))]
      (is (= "Sales" (:value (result doc [5 2])))))))

(deftest malformed-header-imports-and-dimension-removal-are-refused
  (let [doc (base) payload (js/JSON.parse (e/document->json doc))
        invalid (fn [change] (let [copy (js/JSON.parse (js/JSON.stringify payload))]
                              (change copy) (e/json->document (js/JSON.stringify copy))))]
    (is (thrown? js/Error (invalid #(aset % "aliases" #js {"1" "same" "2" "same"}))))
    (is (thrown? js/Error (invalid #(aset % "hyperplanes" #js {"[4,0]" #js {:kind "value" :source "1"}}))))
    (is (thrown? js/Error (invalid #(aset (.-view %) "coord" #js {:hyperplane #js [1 0]}))))
    (is (thrown? js/Error (invalid #(aset (.-cells %) "{\"hyperplane\":[1,0]}" #js {:kind "value" :source "1"}))))
    (is (thrown? js/Error (invalid #(aset % "hyperplanes" #js {"[1,2]" #js {:kind "value" :source "1"}
                                                            "[1, 2]" #js {:kind "value" :source "2"}})))))
  (let [doc (e/put-cell (demo/blank-document "header only" 3) {:hyperplane [3 0]} (value "1"))]
    (is (nil? (e/active-bounds doc)))
    (is (thrown? js/Error (e/resize-dimensions doc 2))))
  (let [doc (e/put-cell (base) {:hyperplane [1 0]} (formula "=> _.offset(0,99).coordinate('date')"))]
    (is (= 0 (:value (result doc {:hyperplane [1 0]}))))))

(deftest references-work-in-formatting-without-misidentifying-headers-as-grid-coordinates
  (let [doc (assoc (e/put-cell (base) [5 2] (value "1")) :rules
                   [{:name "department" :enabled true :coord "(x,y) => y === 2" :value "v => _.value('department') === 'Sales' ? 'color: coral' : ''"}
                    {:name "header" :enabled true :coord "() => _.kind === 'hyperplane'" :value "v => ['header']"}])
        runtime (e/make-runtime doc)]
    (is (re-find #"coral" (:style ((:format runtime) [5 2] ((:evaluate runtime) [5 2])))))
    (let [target {:hyperplane [2 2]} formatted ((:format runtime) target ((:evaluate runtime) target))]
      (is (= ["header"] (:classes formatted)))
      (is (= "" (:style formatted)))
      (is (empty? (:errors formatted))))))

(deftest header-edits-invalidate-values-and-are-undoable
  (remove-watch s/app :persist)
  (s/open-document! (e/put-cell (base) [5 2] (formula "=> $$.department")))
  (is (= "Sales" (:value ((:evaluate (s/runtime)) [5 2]))))
  (s/open-hyperplane! "department" 2 nil)
  (swap! s/app assoc-in [:editor :source] "Engineering")
  (s/save-editor!)
  (is (= "Engineering" (:value ((:evaluate (s/runtime)) [5 2]))))
  (s/undo! false) (is (= "Sales" (:value ((:evaluate (s/runtime)) [5 2]))))
  (s/open-hyperplane! 1 0 "formula")
  (is (= "(dimension,coordinate) => " (get-in @s/app [:editor :source])))
  (when @s/toast-timer (js/clearTimeout @s/toast-timer)))

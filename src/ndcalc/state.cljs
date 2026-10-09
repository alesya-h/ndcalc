(ns ndcalc.state
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [ndcalc.engine :as e]
            [ndcalc.storage :as db]
            [ndcalc.demo :as demo]
            [ndcalc.preview :as preview]))

(defonce app (r/atom {:route :loading :doc nil :documents [] :theme "system" :system-dark false
                     :help false :panel :named :mode :normal :anchor nil :cursor :cell :hyper-dimension nil :plane-zoom 100
                     :named-focus nil :editor nil :dialog nil :command nil
                     :viewport [-1 -1] :grid-size [8 16] :view :plane
                     :cube-axes [1 2 3] :cube-options preview/default-options :cube-fit false :cube-initialized false
                     :hyper-axes [1 2 3 4] :hyper-options preview/hyper-default-options :hyper-fit false :hyper-initialized false
                     :undo [] :redo []
                     :save-status "Opening storage…" :toast nil :error nil}))
(defonce runtime-cache (atom nil))
(defonce last-save (atom (js/Promise.resolve)))
(defonce toast-timer (atom nil))

(defn notify! [message]
  (when @toast-timer (js/clearTimeout @toast-timer))
  (swap! app assoc :toast message)
  (reset! toast-timer (js/setTimeout #(swap! app assoc :toast nil) 4000)))
(defn report! [err] (swap! app assoc :error (or (.-message err) (str err))))
(defn guard! [f] (try (f) (catch :default err (notify! (.-message err)))))

(defn runtime []
  (let [doc (:doc @app) key (select-keys doc [:dimensions :aliases :hyperplanes :cells :named :rules])]
    (when-not (= key (:key @runtime-cache))
      (reset! runtime-cache {:key key :runtime (e/make-runtime doc)}))
    (:runtime @runtime-cache)))

(declare mapping)
(defn numeric-coord [] (get-in @app [:doc :view :coord]))
(defn hyper-axis []
  (when (#{:hyperrow :hypercolumn} (:cursor @app))
    (or (:hyper-dimension @app) (nth (mapping) (if (= :hyperrow (:cursor @app)) 0 1)))))
(defn coord []
  (or (some-> (:named-focus @app) vector)
      (when-let [d (hyper-axis)] {:hyperplane [d (e/axis-value (numeric-coord) d)]})
      (numeric-coord)))
(defn cycle-cursor! []
  (swap! app assoc :cursor (case (:cursor @app) :hyperrow :hypercolumn :hypercolumn :cell :hyperrow)
         :hyper-dimension nil :named-focus nil :anchor nil :mode :normal))
(defn axis-queue [] (e/full-axis-queue (:doc @app)))
(defn volume? [] (boolean (#{:cube :hypercube} (:view @app))))
(defn view-rank [] (case (:view @app) :cube 3 :hypercube 4 2))
(defn view-axes [] (vec (take (view-rank) (concat (if (volume?) (distinct (axis-queue)) (axis-queue)) (repeat 0)))))
(defn mapping [] (vec (take 2 (view-axes))))
(defn axis-order [] (axis-queue))
(defn navigation-axes []
  (cond (volume?) (vec (distinct (axis-queue)))
        (zero? (get-in @app [:doc :dimensions])) [0 0] :else (axis-queue)))
(defn install-axis-queue! [queue clear-named?]
  (let [queue (e/full-axis-queue (assoc-in (:doc @app) [:view :axes] queue))
        slots #(vec (take % (concat (distinct queue) (repeat 0))))]
    (swap! app (fn [state]
                 (cond-> (-> state (assoc :cube-axes (slots 3) :hyper-axes (slots 4))
                             (assoc-in [:doc :view :axes] queue)
                             (assoc-in [:doc :view :mapping] (vec (take 2 (concat queue [0])))))
                   clear-named? (assoc :named-focus nil))))))
(defn install-axis-prefix! [axes clear-named?]
  (install-axis-queue! (into (vec axes) (remove (set axes) (axis-queue))) clear-named?))

(defn selected-coords []
  (if (:named-focus @app) [(coord)]
    (e/block-coords (or (:anchor @app) (coord)) (coord))))

(defn ensure-visible! []
  (when (and (:doc @app) (not (:named-focus @app)))
    (let [[x y] (mapping) c (numeric-coord) [cols rows] (:grid-size @app)
          [left top] (:viewport @app)
          a (e/axis-value c x) b (e/axis-value c y)
          fit (fn [start value size]
                (let [start (cond (< value start) value (>= value (+ start size)) (- value size -1) :else start)]
                  (max (- js/Number.MAX_SAFE_INTEGER) (min (- js/Number.MAX_SAFE_INTEGER (dec size)) start))))]
      (swap! app assoc :viewport [(if (zero? x) 0 (fit left a cols))
                                  (if (zero? y) 0 (fit top b rows))]))))

(defn select! [c extend?]
  (guard!
    #(let [c (e/normalize-coord (get-in @app [:doc :dimensions]) c)]
       (if (e/named? c)
         (swap! app assoc :named-focus (first c) :cursor :cell :hyper-dimension nil :anchor nil :mode :normal)
         (do
           (when (and extend? (nil? (:anchor @app)) (not (:named-focus @app)))
             (swap! app assoc :anchor (coord) :mode :visual))
           (when-not (or extend? (= :visual (:mode @app))) (swap! app assoc :anchor nil))
           (let [c (if-let [d (hyper-axis)] (e/set-axis (numeric-coord) d (e/axis-value c d)) c)]
             (swap! app (fn [s] (-> s (assoc :named-focus nil) (assoc-in [:doc :view :coord] c)))))
           (ensure-visible!))))))

(defn pick-cell! [c extend?]
  (when (not= :cell (:cursor @app))
    (swap! app assoc :cursor :cell :hyper-dimension nil :anchor nil :mode :normal))
  (select! c extend?))
(defn focus-hyperplane! [dimension coordinate orientation extend?]
  (let [target (e/hyperplane-coord (:doc @app) dimension coordinate)
        orientation (or orientation (if (= dimension (first (mapping))) :hyperrow :hypercolumn))
        same? (and (= orientation (:cursor @app)) (= dimension (hyper-axis)))]
    (when-not same? (swap! app assoc :anchor nil :mode :normal))
    (when (and extend? (nil? (:anchor @app)))
      (swap! app assoc :anchor {:hyperplane [dimension (e/axis-value (numeric-coord) dimension)]} :mode :visual))
    (swap! app assoc :cursor orientation :hyper-dimension dimension :named-focus nil)
    (swap! app update-in [:doc :view :coord] e/set-axis dimension (second (:hyperplane target)))
    (ensure-visible!)))

(defn move! [dx dy extend?]
  (if (:named-focus @app) (swap! app assoc :named-focus nil)
    (let [[x y] (mapping) c (numeric-coord)]
      (when (or (nil? (hyper-axis)) (and (pos? x) (= x (hyper-axis)) (not (zero? dx)))
                (and (pos? y) (= y (hyper-axis)) (not (zero? dy))))
        (select! (-> c (e/set-axis x (+ (e/axis-value c x) dx))
                      (e/set-axis y (+ (e/axis-value c y) dy))) extend?)))))

(defn set-slice! [dimension value]
  (when (e/safe-integer? value)
    (select! (e/set-axis (get-in @app [:doc :view :coord]) dimension value) false)))

(defn cube-options []
  (let [options (merge preview/default-options (:cube-options @app))]
    (update options :size #(mapv (fn [d size] (if (zero? d) 1 size)) (:cube-axes @app) %))))
(defn cube-window []
  (preview/window (:doc @app) (:cube-axes @app) (:size (cube-options)) (:cube-fit @app)))

(defn set-cube-option! [key value]
  (guard! #(do (swap! app assoc :cube-options (preview/set-option (cube-options) key value))
               (when (= key :size) (swap! app assoc :cube-fit false)))))

(defn set-cube-camera! [camera]
  (guard! #(swap! app assoc :cube-options
                 (reduce-kv preview/set-option (cube-options) camera))))

(defn set-cube-size! [axis size]
  (set-cube-option! :size (assoc (:size (cube-options)) axis size)))

(defn fit-cube! []
  (if-let [bounds (e/active-bounds (:doc @app))]
    (let [shape (preview/fit-shape (preview/bounds-shape bounds (:cube-axes @app)))]
      (swap! app assoc :cube-fit true :cube-initialized true
             :cube-options (assoc (cube-options) :size shape)))
    (notify! "No populated numeric cells to fit.")))

(defn move-slot! [slot delta extend?]
  (if-let [dimension (get (navigation-axes) (dec slot))]
    (let [c (get-in @app [:doc :view :coord])]
      (when (and (pos? dimension) (or (nil? (hyper-axis)) (= dimension (hyper-axis))))
        (select! (e/set-axis c dimension (+ (e/axis-value c dimension) delta)) extend?)))
    (notify! (str "No dimension in navigation slot " slot "."))))
(defn move-depth! [delta] (move-slot! 3 delta false))

(defn hyper-options []
  (let [options (merge preview/hyper-default-options (:hyper-options @app))]
    (update options :size #(mapv (fn [d size] (if (zero? d) 1 size)) (:hyper-axes @app) %))))
(defn hyper-window []
  (preview/window (:doc @app) (:hyper-axes @app) (:size (hyper-options)) (:hyper-fit @app)))
(defn set-hyper-option! [key value]
  (guard! #(do (swap! app assoc :hyper-options (preview/set-option (hyper-options) key value))
               (when (= key :size) (swap! app assoc :hyper-fit false)))))
(defn set-hyper-size! [axis size]
  (set-hyper-option! :size (assoc (:size (hyper-options)) axis size)))
(defn fit-hyper! []
  (if-let [bounds (e/active-bounds (:doc @app))]
    (swap! app assoc :hyper-fit true :hyper-initialized true
           :hyper-options (assoc (hyper-options) :size (preview/fit-shape (preview/bounds-shape bounds (:hyper-axes @app)))))
    (notify! "No populated numeric cells to fit.")))

(defn refit-view! []
  (when (volume?)
    (let [cube? (= :cube (:view @app)) fit-key (if cube? :cube-fit :hyper-fit)]
      (when (get @app fit-key)
        (if (e/active-bounds (:doc @app)) ((if cube? fit-cube! fit-hyper!))
          (swap! app assoc fit-key false))))))
(defn set-volume-axes! [view axes clear-named?]
  (guard!
    #(let [n (get-in @app [:doc :dimensions]) rank (if (= view :cube) 3 4)]
       (when-not (and (= rank (count axes) (count (set axes)))
                     (every? (fn [d] (and (e/safe-integer? d) (<= 0 d n))) axes))
         (e/fail "Preview axes must be distinct dimensions (including null)."))
       (install-axis-prefix! axes clear-named?)
       (ensure-visible!) (refit-view!))))
(defn set-cube-axes!
  ([axes] (set-cube-axes! axes true))
  ([axes clear-named?] (set-volume-axes! :cube axes clear-named?)))
(declare set-mapping!)
(defn set-hyper-axis! [axis d] (set-mapping! axis d))

(defn sync-cube! []
  (when (< (inc (get-in @app [:doc :dimensions])) (view-rank)) (swap! app assoc :view :plane))
  (install-axis-queue! (axis-queue) false)
  (refit-view!))

(defn switch! [d]
  (when (and (e/safe-integer? d) (<= 0 d (get-in @app [:doc :dimensions])))
    (if (volume?)
      (set-volume-axes! (:view @app) (preview/enqueue-dimension (view-axes) d) true)
      (do
        (install-axis-prefix! (e/switch-dimension (mapping) d) true)
        (let [[x y] (mapping)]
          (swap! app assoc :viewport [(dec (e/axis-value (numeric-coord) x)) (dec (e/axis-value (numeric-coord) y))]))
        (ensure-visible!)))))

(defn set-cube-axis! [axis d] (set-mapping! axis d))

(defn set-mapping! [axis d]
  (guard! #(let [d (e/resolve-dimension (:doc @app) d)]
             (when-not (= d (nth (view-axes) axis))
               (if (and (not (volume?)) (zero? d))
                 (install-axis-prefix! (assoc (mapping) axis 0) true)
                 (install-axis-queue! (preview/replace-axis (if (volume?) (vec (distinct (axis-queue))) (axis-queue)) axis d) true))
               (ensure-visible!) (refit-view!)))))

(defn change! [f]
  (let [old (:doc @app) new (f old)]
    (when-not (= old new)
      (swap! app (fn [s] (-> s
                            (assoc :doc (assoc new :updatedAt (.now js/Date)) :redo [])
                            (update :undo #(vec (take-last 100 (conj % old)))))))
      (sync-cube!))))

(defn undo! [redo?]
  (when (#{:plane :cube :hypercube} (:view @app))
   (let [from (if redo? :redo :undo) to (if redo? :undo :redo)
        snapshot (peek (get @app from))]
    (when snapshot
      (swap! app (fn [s] (-> s
                            (update to conj (:doc s)) (update from pop)
                            (assoc :doc (assoc snapshot :updatedAt (.now js/Date))
                                   :anchor nil :named-focus nil :mode :normal :editor nil))))
      (ensure-visible!)
      (sync-cube!)))))

(defn editable? [] (boolean (and (= :editor (:route @app)) (:doc @app) (#{:plane :cube :hypercube} (:view @app)))))
(defn formula-template [dimensions named?]
  (let [args (if named? ["name"]
              (mapv #(if (< % 26) (js/String.fromCharCode (+ 97 %)) (str "d" (inc %)))
                    (range dimensions)))]
    (str "(" (str/join "," (conj args "...rest")) ") => ")))

(defn editor-template [editor]
  (if (some-> editor :coords first e/hyperplane?) "(dimension,coordinate) => "
    (formula-template (get-in @app [:doc :dimensions])
                      (or (:new-name editor) (some-> editor :coords first e/named?)))))

(defn present-editor [editor kind]
  (let [old (:kind editor) source (:source editor)
        source (cond (= old kind) source
                     (= old "text") (js/JSON.stringify source)
                     (= kind "text") (or (:text (e/literal-string {:kind "value" :source source})) source)
                     :else source)]
    (cond-> (assoc editor :kind kind :source source :error nil)
      (and (= kind "formula") (or (str/blank? source) (and (= old "text") (str/blank? (:source editor)))))
      (assoc :source (editor-template editor) :focus-source true))))
(defn set-editor-kind! [kind] (swap! app update :editor present-editor kind))
(defn make-editor [coords cell kind]
  (let [editor {:coords coords :kind (:kind cell) :source (or (:source cell) "") :error nil
                :width (or (e/cell-width (:doc @app) (first coords)) "")}
        kind (or kind (when (e/literal-string cell) "text") (:kind cell) "value")]
    (present-editor editor kind)))

(defn open-editor! [kind]
  (if-not (editable?) (notify! "Open a table to edit.")
    (guard!
      #(let [coords (selected-coords) cell (e/cell-at (:doc @app) (first coords))
             editor (make-editor coords cell kind)]
         (swap! app assoc :editor editor :command nil)))))

(defn open-hyperplane! [dimension coordinate kind]
  (guard! #(let [target (e/hyperplane-coord (:doc @app) dimension coordinate)
                 cell (e/cell-at (:doc @app) target)]
             (swap! app assoc :editor (make-editor [target] cell kind)))))

(defn new-named! []
  (if-not (editable?) (notify! "Open a table to create named cells.")
    (swap! app assoc :editor {:new-name true :name "" :kind "value" :source "" :error nil})))

(defn save-editor! []
  (let [{:keys [coords kind source new-name name width]} (:editor @app)]
    (try
      (let [width-only? (and (not new-name) (= kind "value") (str/blank? source)
                            (every? #(nil? (e/cell-at (:doc @app) %)) coords))
            cell (when-not width-only?
                   (e/validate-cell! {:kind (if (= kind "text") "value" kind)
                                     :source (if (= kind "text") (js/JSON.stringify source) source)}))
            coords (if new-name [[(str/trim name)]] coords)]
        (when new-name
          (e/normalize-coord (get-in @app [:doc :dimensions]) (first coords))
          (when (contains? (get-in @app [:doc :named]) (str/trim name)) (e/fail "That name already exists.")))
        (let [width (when-not (or (nil? width) (= "" width)) (js/Number width))]
          (change! #(reduce (fn [d c] (-> d (e/put-cell c cell) (e/set-cell-width c width))) % coords)))
        (swap! app assoc :editor nil :mode :normal :anchor nil)
        (notify! (str "Saved " (count coords) " cell" (when (> (count coords) 1) "s"))))
      (catch :default err (swap! app assoc-in [:editor :error] (.-message err))))))

(defn set-cell-width! [target width]
  (guard! #(change! (fn [doc] (e/set-cell-width doc target width)))))

(defn clear! []
  (when (editable?)
    (guard! #(let [coords (selected-coords)]
               (change! (fn [doc] (reduce (fn [d c] (e/put-cell d c nil)) doc coords)))
               (swap! app assoc :anchor nil :mode :normal :named-focus nil)))))

(defn clear-axis! [axis]
  (when (and (editable?) (not (:named-focus @app)))
    (let [dimension (nth (mapping) axis) other (nth (mapping) (- 1 axis)) c (numeric-coord)]
      (if (or (hyper-axis) (zero? dimension)) (clear!)
        (do
          (change! (fn [doc]
                     (reduce (fn [d key]
                               (let [at (e/key-coord key)]
                                 (if (and (not (e/named? at)) (not (e/hyperplane? at))
                                          (every? (fn [i] (or (= (inc i) other) (= (nth at i) (nth c i))))
                                                  (range (count c))))
                                   (e/put-cell d at nil) d)))
                             doc (distinct (concat (keys (:cells doc)) (keys (:cell-widths doc)))))))
          (notify! (str "Cleared " (if (zero? axis) "column" "row") " at " (e/axis-value c dimension))))))))

(defn jump! [axis value]
  (when-not (e/safe-integer? value) (e/fail "Coordinate must be a safe integer."))
  (swap! app assoc :named-focus nil)
  (select! (e/set-axis (numeric-coord) (nth (mapping) axis) value) false))
(defn jump-row! [end? extend?]
  (when (= :plane (:view @app))
    (let [[x _] (mapping) c (get-in @app [:doc :view :coord])
          coords (filter (fn [at] (every? (fn [i] (or (= (inc i) x) (= (nth at i) (nth c i))))
                                         (range (count c))))
                         (map e/key-coord (keys (get-in @app [:doc :cells]))))]
      (if (seq coords)
        (select! (e/set-axis c x (apply (if end? max min) (map #(e/axis-value % x) coords))) extend?)
        (notify! "This row has no populated cells.")))))

(defn jump-bound! [axis end?]
  (let [bounds (e/active-bounds (:doc @app)) dimension (nth (mapping) axis)]
    (when (and bounds (pos? dimension))
      (jump! axis (nth (if end? (:end bounds) (:start bounds)) (dec dimension))))))

(defn clipboard-axes [dimensions mapping]
  ;; Leading slots follow X/Y, X/Y/Z or X/Y/Z/W; remaining dimensions
  ;; follow ascending dimension order. Every null slot has extent one.
  (into (vec mapping) (remove (set mapping) (range 1 (inc dimensions)))))

(defn yank! []
  (guard!
    #(let [coords (selected-coords) origin (first coords) hyper? (e/hyperplane? origin) named? (e/named? origin)
           axes (if (or hyper? named?) [] (clipboard-axes (get-in @app [:doc :dimensions]) (view-axes)))
           shape (if named? [] (e/block-shape origin (last coords)))
           entries (mapv (fn [c]
                           {:offset (if hyper? [(- (second (:hyperplane c)) (second (:hyperplane origin)))]
                                     (mapv (fn [axis] (- (e/axis-value c axis) (e/axis-value origin axis))) axes))
                            :cell (e/cell-at (:doc @app) c) :width (e/cell-width (:doc @app) c)}) coords)]
       (swap! app assoc :clipboard {:kind (if hyper? :hyperplane :cells) :cells entries
                                    :shape (if hyper? shape (mapv (fn [axis] (if (zero? axis) 1 (nth shape (dec axis)))) axes))}
              :anchor nil :mode :normal)
       (notify! (str "Copied " (count coords) " cell(s); p to paste.")))))

(defn paste! []
  (when (editable?)
    (guard!
      #(if-let [{:keys [cells shape kind]} (:clipboard @app)]
         (let [c (coord) named? (e/named? c) hyper? (e/hyperplane? c)
               line? (or hyper? (= kind :hyperplane))
               axes (if (or hyper? named?) [] (clipboard-axes (get-in @app [:doc :dimensions]) (view-axes)))
               line-axis (first (filter pos? axes))]
           (when (or (and line? (> (count (filter (fn [size] (> size 1)) shape)) 1))
                     (and named? (> (count cells) 1))
                     (and hyper? (zero? (first (:hyperplane c))) (> (count cells) 1))
                     (and (= kind :hyperplane) (not hyper?) (not named?) (nil? line-axis) (> (count cells) 1))
                     (and (not line?) (some (fn [[index size]] (and (> size 1) (zero? (get axes index 0))))
                                           (map-indexed vector shape))))
             (e/fail "This selection won't fit in the target dimensions or named cell."))
           (change! (fn [doc]
                      (reduce (fn [d [index {:keys [offset cell width]}]]
                                (let [target (cond named? c
                                                   hyper? (e/hyperplane-coord d (first (:hyperplane c)) (+ (second (:hyperplane c)) index))
                                                   line? (if line-axis (e/set-axis c line-axis (+ (e/axis-value c line-axis) index)) c)
                                                   :else (reduce (fn [at [i axis]] (e/set-axis at axis (+ (e/axis-value c axis) (get offset i 0))))
                                                                 c (map-indexed vector axes)))]
                                  (-> d (e/put-cell target cell) (e/set-cell-width target width)))) doc (map-indexed vector cells))))
           (notify! "Pasted. Formulas keep their source and use their new coordinates."))
         (notify! "Nothing copied yet. Use y to copy a cell or block.")))))

(defn open-rule! [rule]
  (when (editable?)
    (swap! app assoc :rule-editor (or rule {:id (str (random-uuid)) :name "New rule" :enabled true
                                          :coord "(...coord) => true" :value "value => ['heading']"}))))
(defn save-rule! []
  (let [rule (:rule-editor @app)]
    (try
      (when (str/blank? (:name rule)) (e/fail "Name your rule."))
      (doseq [source [(:coord rule) (:value rule)]] (e/validate-cell! {:kind "value" :source source}))
      (change! (fn [doc]
                 (update doc :rules
                         #(if (some (fn [r] (= (:id r) (:id rule))) %)
                            (mapv (fn [r] (if (= (:id r) (:id rule)) (dissoc rule :error) r)) %)
                            (conj % (dissoc rule :error))))))
      (swap! app assoc :rule-editor nil)
      (catch :default err (swap! app assoc-in [:rule-editor :error] (.-message err))))))
(defn reorder-rule! [from to]
  (let [rules (get-in @app [:doc :rules])]
    (when (and (editable?) (<= 0 from) (< from (count rules)) (<= 0 to) (< to (count rules)))
      (change! (fn [doc]
                 (let [item (nth rules from) remaining (vec (concat (subvec rules 0 from) (subvec rules (inc from))))]
                   (assoc doc :rules (vec (concat (subvec remaining 0 to) [item] (subvec remaining to))))))))))

(defn open-document! [doc]
  (swap! app assoc :doc doc :route :editor :anchor nil :mode :normal :named-focus nil :cursor :cell :hyper-dimension nil
         :editor nil :rule-editor nil :dialog nil :command nil :undo [] :redo [] :view :plane
         :cube-axes [1 2 3] :cube-fit false :cube-initialized false
         :hyper-axes [1 2 3 4] :hyper-fit false :hyper-initialized false :css-draft (:css doc))
  (install-axis-queue! (axis-queue) false)
  (let [[x y] (mapping)]
    (swap! app assoc :viewport [(dec (e/axis-value (numeric-coord) x)) (dec (e/axis-value (numeric-coord) y))]))
  (ensure-visible!))

(defn open-example! [make]
  (open-document! (make))
  (swap! app assoc :viewport [0 0] :panel :named))
(defn open-color-example! [] (open-example! demo/color-document))

(defn refresh-documents! []
  (.then (db/all-documents!) #(swap! app assoc :documents %)))
(defn home! []
  (swap! app assoc :route :home :editor nil :rule-editor nil :dialog nil :command nil)
  (-> @last-save (.then (fn [_] (refresh-documents!))) (.catch report!)))

(defn create-document! [title n]
  (guard! #(do (when (str/blank? title) (e/fail "Give your table a name."))
               (when-not (and (e/safe-integer? n) (<= 0 n 32)) (e/fail "Choose 0–32 dimensions."))
               (open-document! (demo/blank-document (str/trim title) n)))))

(defn delete-document! [doc]
  (-> (db/delete-document! (:id doc))
      (.then (fn [_]
               (when (= (:id doc) (get-in @app [:doc :id])) (swap! app assoc :doc nil))
               (refresh-documents!)))
      (.catch report!)))

(defn download! [doc]
  (let [blob (js/Blob. #js [(e/document->json doc)] #js {:type "application/json"})
        url (.createObjectURL js/URL blob) link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) (str (str/replace (:title doc) #"[^\w\-]+" "-") ".ndcalc.json"))
    (.click link)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)))

(defn import-file! [file]
  (when file
    (if (> (.-size file) (* 10 1024 1024)) (notify! "Imports are limited to 10 MB.")
      (-> (.text file)
          (.then (fn [text]
                   (let [doc (e/json->document text)]
                     (swap! app assoc :dialog {:type :trust :document doc}))))
          (.catch #(notify! (str "Import failed: " (.-message %))))))))

(defn set-theme! [theme]
  (when (#{"system" "light" "dark"} theme) (swap! app assoc :theme theme)))
(defn resolved-theme []
  (if (= "system" (:theme @app)) (if (:system-dark @app) "dark" "light") (:theme @app)))
(defn toggle-theme! []
  (set-theme! (case (:theme @app) "system" "light" "light" "dark" "system")))
(defonce system-media (atom nil))
(defn install-system-theme! []
  (when-let [[media listener] @system-media] (.removeEventListener media "change" listener))
  (let [media (.matchMedia js/window "(prefers-color-scheme: dark)")
        listener #(swap! app assoc :system-dark (.-matches %))]
    (swap! app assoc :system-dark (.-matches media))
    (.addEventListener media "change" listener)
    (reset! system-media [media listener])))
(defn toggle-panel! [panel]
  (swap! app update :panel #(when-not (= % panel) panel)))
(defn set-view! [view]
  (let [rank (case view :cube 3 :hypercube 4 2) n (get-in @app [:doc :dimensions])]
    (when (or (= view :plane) (>= (inc n) rank))
      ;; View changes reveal/hide a prefix of ONE queue. Never reconstruct W.
      (install-axis-queue! (axis-queue) false)
      (swap! app assoc :view view :named-focus nil :editor nil)
      (when (volume?)
        (let [init-key (if (= view :cube) :cube-initialized :hyper-initialized)]
          (if (get @app init-key) (refit-view!)
            (do (swap! app assoc init-key true)
                (when (e/active-bounds (:doc @app)) ((if (= view :cube) fit-cube! fit-hyper!)))))))
      (ensure-visible!))))
(defn toggle-3d! [] (set-view! (if (= :cube (:view @app)) :plane :cube)))
(defn toggle-4d! [] (set-view! (if (= :hypercube (:view @app)) :plane :hypercube)))
(defn cycle-view! []
  (let [n (get-in @app [:doc :dimensions])]
    (set-view! (case (:view @app)
                 :plane (if (>= n 2) :cube :plane)
                 :cube (if (>= n 3) :hypercube :plane)
                 :plane))))
(defn fit-volume! [] (when (volume?) ((if (= :cube (:view @app)) fit-cube! fit-hyper!))))
(defn toggle-follow! []
  (when (volume?)
    (let [key (if (= :cube (:view @app)) :cube-fit :hyper-fit)]
      (if (get @app key) (swap! app assoc key false) (fit-volume!)))))
(defn toggle-labels! []
  (when (volume?)
    (let [cube? (= :cube (:view @app)) options ((if cube? cube-options hyper-options))]
      ((if cube? set-cube-option! set-hyper-option!) :labels (not (:labels options))))))
(defn view-zoom [] (case (:view @app) :cube (:zoom (cube-options)) :hypercube (:zoom (hyper-options)) (or (:plane-zoom @app) 100)))
(defn set-view-zoom! [value]
  (let [value (max 25 (min 200 (js/Math.round value)))]
    (case (:view @app) :cube (set-cube-option! :zoom value) :hypercube (set-hyper-option! :zoom value)
          (swap! app assoc :plane-zoom value))))
(defn pan-plane! [dx dy]
  (let [[x y] (mapping) limit js/Number.MAX_SAFE_INTEGER]
    (swap! app update :viewport
           (fn [[a b]] [(if (zero? x) 0 (max (- limit) (min (- limit 24) (+ a dx))))
                        (if (zero? y) 0 (max (- limit) (min (- limit 40) (+ b dy))))]))))
(defn consume! [event]
  (.preventDefault event)
  (when (fn? (.-stopPropagation event)) (.stopPropagation event)))
(defn preview-wheel! [event]
  (let [cube? (= :cube (:view @app))
        key (cond (.-altKey event) :zoom
                  (and cube? (.-ctrlKey event)) :gap
                  (and cube? (.-shiftKey event) (= "stack" (:layout (cube-options)))) :transparency)]
   (when (and key (editable?) (not-any? @app [:editor :rule-editor :dialog]))
    (consume! event)
    (let [options (case (:view @app) :cube (cube-options) :hypercube (hyper-options) {:zoom (view-zoom)})
          raw (if (zero? (.-deltaY event)) (.-deltaX event) (.-deltaY event))
          delta (* raw (case (.-deltaMode event) 1 16 2 240 1))
          step (* (js/Math.sign delta) (max 1 (min 10 (js/Math.round (/ (abs delta) 12)))))
          [low high] (get preview/option-ranges key)
          value (max low (min high (+ (get options key) (* step (if (= key :transparency) 1 -1)))))]
      (when-not (zero? delta)
        (if (= key :zoom) (set-view-zoom! value) (set-cube-option! key value)))
      true))))
(defn permute-axes! []
  (let [old-axes (view-axes) axes (preview/next-permutation old-axes)]
    (if (volume?) (set-volume-axes! (:view @app) axes true)
      (do (install-axis-prefix! axes true) (ensure-visible!)))))
(defn toggle-cube-layout! []
  (when (= :cube (:view @app))
    (set-cube-option! :layout (if (= "stack" (:layout (cube-options))) "slices" "stack"))))

(defn toggle-visual! []
  (when (and (editable?) (not (:named-focus @app)))
    (if (= :visual (:mode @app)) (swap! app assoc :mode :normal :anchor nil)
      (swap! app assoc :mode :visual :anchor (coord)))))

(defn command-key! [key]
  (let [{:keys [text axis]} (:command @app)]
    (cond
      (= key "Escape") (swap! app assoc :command nil)
      (= key "Backspace") (swap! app update-in [:command :text] #(subs % 0 (max 0 (dec (count %)))))
      (= key "Enter") (guard! #(do
                                  (when-not (re-matches #"-?\d+" text) (e/fail "Enter a signed integer, e.g. -15."))
                                  (jump! axis (js/Number text))
                                  (swap! app assoc :command nil)))
      (re-matches #"[0-9-]" key) (swap! app update-in [:command :text] str key))))

(defn typing-target? [event]
  (let [el (.-target event)]
    (or (#{"INPUT" "TEXTAREA" "SELECT"} (.-tagName el)) (.-isContentEditable el))))

(defn keydown! [event]
  (let [key (.-key event) ctrl (or (.-ctrlKey event) (.-metaKey event))
        alt (.-altKey event) shift (.-shiftKey event) state @app]
    (cond
      (= key "Escape")
      (do (.preventDefault event)
          (swap! app assoc :editor nil :rule-editor nil :dialog nil :command nil :anchor nil :mode :normal)
          (when (typing-target? event) (.blur (.-target event))))
      (or (:editor state) (:rule-editor state) (:dialog state)) nil
      (typing-target? event) nil
      (and (not shift) (not alt) (not ctrl) (= "BUTTON" (.. event -target -tagName))
           (not (some-> (.-target event) (aget "dataset") (aget "hyperplane"))) (#{"Enter" " "} key)) nil
      (and (not ctrl) (not alt) (#{"h" "?"} key))
      (do (.preventDefault event) (swap! app update :help not))
      (not= :editor (:route state)) nil
      (:command state) (do (.preventDefault event) (command-key! key))
      (#{"ArrowLeft" "ArrowRight" "ArrowUp" "ArrowDown"} key)
      (do (.preventDefault event)
          (if (or ctrl alt)
            (let [vertical? (#{"ArrowUp" "ArrowDown"} key)
                  base (cond (and ctrl alt) 7 alt 5 :else 3)
                  delta (if (#{"ArrowUp" "ArrowRight"} key) 1 -1)]
              (move-slot! (+ base (if vertical? 0 1)) delta shift))
            (case key "ArrowLeft" (move! -1 0 shift) "ArrowRight" (move! 1 0 shift)
                      "ArrowUp" (move! 0 -1 shift) "ArrowDown" (move! 0 1 shift))))
      (and (not ctrl) (not alt) (#{"PageUp" "PageDown"} key))
      (do (.preventDefault event) (move-slot! 3 (if (= key "PageUp") 1 -1) shift))
      (and (not ctrl) (not alt) (#{"Home" "End"} key))
      (when (= :plane (:view state)) (.preventDefault event) (jump-row! (= key "End") shift))
      (and ctrl (not alt) (= (str/lower-case key) "s"))
      (do (.preventDefault event) (notify! "Changes save automatically to IndexedDB."))
      (and ctrl (not alt) (= (str/lower-case key) "z")) (do (.preventDefault event) (undo! shift))
      (and ctrl (not alt) (= (str/lower-case key) "y")) (do (.preventDefault event) (undo! true))
      (and ctrl (not alt) (= (str/lower-case key) "v")) (do (.preventDefault event) (toggle-visual!))
      (and alt (= key "Enter")) (do (consume! event) (open-editor! "text"))
      (= key "Alt") (consume! event)
      (or ctrl alt) nil
      (re-matches #"[0-9]" key) (do (.preventDefault event) (switch! (js/Number key)))
      :else
      (when (#{"Tab" "Enter" "i" "I" "f" "F" "l" "v" "y" "p" "u" "Delete" "Backspace"
               "g" "G" "b" "B" "e" "E" "d" "D" "n" "N" "c" "r" "t" "T" "H"} key)
        (.preventDefault event)
        (case key
          "Tab" (move! (if shift -1 1) 0 false)
          "Enter" (open-editor! (when shift "formula")) "i" (open-editor! nil)
          "I" (open-editor! "formula") "f" (toggle-follow!) "F" (fit-volume!) "l" (toggle-labels!)
          "v" (toggle-visual!) "y" (yank!) "p" (paste!) "u" (undo! false)
          ("Delete" "Backspace") (clear!)
          "g" (swap! app assoc :command {:axis 0 :text ""})
          "G" (swap! app assoc :command {:axis 1 :text ""})
          "b" (jump-bound! 0 false) "B" (jump-bound! 1 false)
          "e" (jump-bound! 0 true) "E" (jump-bound! 1 true)
          "d" (clear-axis! 0) "D" (clear-axis! 1)
          "n" (toggle-panel! :named) "N" (new-named!)
          "c" (toggle-panel! :css) "r" (toggle-panel! :rules)
          "t" (cycle-view!) "T" (permute-axes!) "H" (cycle-cursor!) nil)))))

(defn install-persistence! []
  (add-watch app :persist
             (fn [_ _ old new]
               (when (and (:doc new) (not= (:doc old) (:doc new)))
                 (let [doc (:doc new)]
                   (swap! app assoc :save-status "Saving…")
                   (reset! last-save
                           (-> (db/save-document! doc)
                               (.then (fn [_]
                                        (when (= doc (:doc @app)) (swap! app assoc :save-status "Saved locally"))))
                               (.catch (fn [err]
                                         (swap! app assoc :save-status "Save failed")
                                         (report! err)))))))
               (when (or (not= (:theme old) (:theme new))
                         (not= (:plane-zoom old) (:plane-zoom new))
                         (not= (:cube-options old) (:cube-options new))
                         (not= (:hyper-options old) (:hyper-options new)))
                 (.catch (db/save-preferences! {:theme (:theme new) :plane-zoom (:plane-zoom new)
                                               :cube-options (merge preview/default-options (:cube-options new))
                                               :hyper-options (merge preview/hyper-default-options (:hyper-options new))}) report!)))))

(defn init! []
  (-> (db/open!)
      (.then (fn [_] (db/preferences!)))
      (.then (fn [prefs]
               (when (#{"system" "dark" "light"} (:theme prefs)) (swap! app assoc :theme (:theme prefs)))
               (when (and (e/safe-integer? (:plane-zoom prefs)) (<= 25 (:plane-zoom prefs) 200))
                 (swap! app assoc :plane-zoom (:plane-zoom prefs)))
               (swap! app assoc :cube-options (preview/restore-options (:cube-options prefs))
                                :hyper-options (preview/restore-options (:hyper-options prefs) preview/hyper-default-options))
               (install-persistence!)
               (db/all-documents!)))
      (.then (fn [docs]
               (swap! app assoc :documents docs)
               (if (empty? docs) (open-document! (demo/demo-document))
                 (swap! app assoc :route :home :save-status "Saved locally"))))
      (.catch (fn [err] (report! err) (swap! app assoc :route :home :save-status "Storage unavailable")))))

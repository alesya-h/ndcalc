(ns ndcalc.state
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [ndcalc.engine :as e]
            [ndcalc.storage :as db]
            [ndcalc.demo :as demo]
            [ndcalc.preview :as preview]))

(defonce app (r/atom {:route :loading :doc nil :documents [] :theme "dark"
                     :help false :panel :named :mode :normal :anchor nil
                     :named-focus nil :editor nil :dialog nil :command nil
                     :viewport [-1 -1] :grid-size [8 16] :view :plane
                     :cube-axes [1 2 3] :cube-options preview/default-options :cube-fit false :cube-initialized false
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
  (let [doc (:doc @app) key (select-keys doc [:dimensions :cells :named :rules])]
    (when-not (= key (:key @runtime-cache))
      (reset! runtime-cache {:key key :runtime (e/make-runtime doc)}))
    (:runtime @runtime-cache)))

(defn coord [] (or (some-> (:named-focus @app) vector) (get-in @app [:doc :view :coord])))
(defn mapping [] (get-in @app [:doc :view :mapping]))
(defn selected-coords []
  (if (:named-focus @app) [(coord)]
    (e/block-coords (or (:anchor @app) (coord)) (coord))))

(defn ensure-visible! []
  (when (and (:doc @app) (not (:named-focus @app)))
    (let [[x y] (mapping) c (coord) [cols rows] (:grid-size @app)
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
         (swap! app assoc :named-focus (first c) :anchor nil :mode :normal)
         (do
           (when (and extend? (nil? (:anchor @app)) (not (:named-focus @app)))
             (swap! app assoc :anchor (coord) :mode :visual))
           (when-not (or extend? (= :visual (:mode @app))) (swap! app assoc :anchor nil))
           (swap! app (fn [s] (-> s (assoc :named-focus nil) (assoc-in [:doc :view :coord] c))))
           (ensure-visible!))))))

(defn move! [dx dy extend?]
  (if (:named-focus @app) (swap! app assoc :named-focus nil)
    (let [[x y] (mapping) c (coord)]
      (select! (-> c
                   (e/set-axis x (+ (e/axis-value c x) dx))
                   (e/set-axis y (+ (e/axis-value c y) dy))) extend?))))

(defn set-slice! [dimension value]
  (when (e/safe-integer? value)
    (select! (e/set-axis (get-in @app [:doc :view :coord]) dimension value) false)))

(defn cube-options [] (merge preview/default-options (:cube-options @app)))
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

(defn move-depth! [delta]
  (let [z (nth (:cube-axes @app) 2) c (get-in @app [:doc :view :coord])]
    (select! (e/set-axis c z (+ (e/axis-value c z) delta)) false)))

(defn set-cube-axes!
  ([axes] (set-cube-axes! axes true))
  ([axes clear-named?]
   (guard!
     #(let [n (get-in @app [:doc :dimensions])]
        (when-not (and (= 3 (count axes)) (= 3 (count (set axes)))
                       (every? (fn [d] (and (e/safe-integer? d) (<= 1 d n))) axes))
          (e/fail "3D needs three distinct, non-null dimensions."))
        (swap! app (fn [s] (cond-> (-> s (assoc :cube-axes axes)
                                         (assoc-in [:doc :view :mapping] (vec (take 2 axes))))
                            clear-named? (assoc :named-focus nil))))
        (ensure-visible!)
        (when (:cube-fit @app)
          (if (e/active-bounds (:doc @app)) (fit-cube!)
            (swap! app assoc :cube-fit false)))))))

(defn sync-cube! []
  (when (= :cube (:view @app))
    (let [n (get-in @app [:doc :dimensions])]
      (if (< n 3) (swap! app assoc :view :plane)
        (set-cube-axes!
          (vec (take 3 (distinct (filter #(and (e/safe-integer? %) (<= 1 % n))
                                        (concat (mapping) (:cube-axes @app) (range 1 (inc n))))))) false)))))

(defn switch! [d]
  (when (and (e/safe-integer? d) (<= 0 d (get-in @app [:doc :dimensions])))
    (if (= :cube (:view @app))
      (if (zero? d) (notify! "The null dimension is ignored in 3D.")
        (set-cube-axes! (preview/enqueue-dimension (:cube-axes @app) d)))
      (do
        (swap! app (fn [s] (-> s (assoc :named-focus nil)
                               (update-in [:doc :view :mapping] e/switch-dimension d))))
        (let [[x y] (mapping)]
          (swap! app assoc :viewport [(dec (e/axis-value (coord) x)) (dec (e/axis-value (coord) y))]))
        (ensure-visible!)))))

(defn set-cube-axis! [axis d]
  (set-cube-axes! (preview/replace-axis (:cube-axes @app) axis d)))

(defn set-mapping! [axis d]
  (if (= :cube (:view @app))
    (if (zero? d) (notify! "The null dimension is ignored in 3D.") (set-cube-axis! axis d))
    (let [other (if (= axis 0) 1 0) m (mapping)
          m (if (and (pos? d) (= d (nth m other))) (assoc m other (nth m axis)) m)]
      (swap! app assoc-in [:doc :view :mapping] (assoc m axis d))
      (swap! app assoc :named-focus nil)
      (ensure-visible!))))

(defn change! [f]
  (let [old (:doc @app) new (f old)]
    (when-not (= old new)
      (swap! app (fn [s] (-> s
                            (assoc :doc (assoc new :updatedAt (.now js/Date)) :redo [])
                            (update :undo #(vec (take-last 100 (conj % old)))))))
      (sync-cube!))))

(defn undo! [redo?]
  (when (#{:plane :cube} (:view @app))
   (let [from (if redo? :redo :undo) to (if redo? :undo :redo)
        snapshot (peek (get @app from))]
    (when snapshot
      (swap! app (fn [s] (-> s
                            (update to conj (:doc s)) (update from pop)
                            (assoc :doc (assoc snapshot :updatedAt (.now js/Date))
                                   :anchor nil :named-focus nil :mode :normal :editor nil))))
      (ensure-visible!)
      (sync-cube!)))))

(defn editable? [] (boolean (and (= :editor (:route @app)) (:doc @app) (#{:plane :cube} (:view @app)))))
(defn formula-template [dimensions named?]
  (let [args (if named? ["name"]
              (mapv #(if (< % 26) (js/String.fromCharCode (+ 97 %)) (str "d" (inc %)))
                    (range dimensions)))]
    (str "(" (str/join "," (conj args "...rest")) ") => ")))

(defn editor-template [editor]
  (formula-template (get-in @app [:doc :dimensions])
                    (or (:new-name editor) (some-> editor :coords first e/named?))))

(defn set-editor-kind! [kind]
  (swap! app update :editor
         (fn [editor]
           (cond-> (assoc editor :kind kind :error nil)
             (and (= kind "formula") (str/blank? (:source editor)))
             (assoc :source (editor-template editor) :focus-source true)))))

(defn open-editor! [kind]
  (if-not (editable?) (notify! "Open a table to edit.")
    (guard!
      #(let [coords (selected-coords) cell (e/cell-at (:doc @app) (first coords))
             kind (or kind (:kind cell) "value")
             editor {:coords coords :kind kind :source (or (:source cell) "") :error nil}
             editor (if (and (= kind "formula") (str/blank? (:source editor)))
                      (assoc editor :source (editor-template editor) :focus-source true) editor)]
         (swap! app assoc :editor editor :command nil)))))

(defn new-named! []
  (if-not (editable?) (notify! "Open a table to create named cells.")
    (swap! app assoc :editor {:new-name true :name "" :kind "value" :source "" :error nil})))

(defn save-editor! []
  (let [{:keys [coords kind source new-name name]} (:editor @app)]
    (try
      (let [cell (e/validate-cell! {:kind kind :source source})
            coords (if new-name [[(str/trim name)]] coords)]
        (when new-name
          (e/normalize-coord (get-in @app [:doc :dimensions]) (first coords))
          (when (contains? (get-in @app [:doc :named]) (str/trim name)) (e/fail "That name already exists.")))
        (change! #(reduce (fn [d c] (e/put-cell d c cell)) % coords))
        (swap! app assoc :editor nil :mode :normal :anchor nil)
        (notify! (str "Saved " (count coords) " cell" (when (> (count coords) 1) "s"))))
      (catch :default err (swap! app assoc-in [:editor :error] (.-message err))))))

(defn clear! []
  (when (editable?)
    (guard! #(let [coords (selected-coords)]
               (change! (fn [doc] (reduce (fn [d c] (e/put-cell d c nil)) doc coords)))
               (swap! app assoc :anchor nil :mode :normal :named-focus nil)))))

(defn clear-axis! [axis]
  (when (and (editable?) (not (:named-focus @app)))
    (let [dimension (nth (mapping) axis) other (nth (mapping) (- 1 axis)) c (coord)]
      (if (zero? dimension) (clear!)
        (do
          (change! (fn [doc]
                     (update doc :cells
                             #(into {} (remove
                                         (fn [[key _]]
                                           (let [at (e/key-coord key)]
                                             (every? (fn [i] (or (= (inc i) other) (= (nth at i) (nth c i))))
                                                     (range (count c))))) %)))))
          (notify! (str "Cleared " (if (zero? axis) "column" "row") " at " (e/axis-value c dimension))))))))

(defn jump! [axis value]
  (when-not (e/safe-integer? value) (e/fail "Coordinate must be a safe integer."))
  (swap! app assoc :named-focus nil)
  (select! (e/set-axis (coord) (nth (mapping) axis) value) false))
(defn jump-bound! [axis end?]
  (let [bounds (e/active-bounds (:doc @app)) dimension (nth (mapping) axis)]
    (when (and bounds (pos? dimension))
      (jump! axis (nth (if end? (:end bounds) (:start bounds)) (dec dimension))))))

(defn clipboard-axes [dimensions mapping]
  ;; Leading slots follow the view's X/Y or X/Y/Z axes; remaining dimensions
  ;; follow ascending dimension order. Null slots are retained in the plane.
  (into (vec mapping) (remove (set mapping) (range 1 (inc dimensions)))))

(defn yank! []
  (guard!
    #(let [coords (selected-coords) named? (:named-focus @app)
           origin (first coords)
           axes (if named? [] (clipboard-axes (get-in @app [:doc :dimensions])
                                            (if (= :cube (:view @app)) (:cube-axes @app) (mapping))))
           shape (if named? [] (e/block-shape origin (last coords)))
           entries (mapv (fn [c]
                           {:offset (mapv (fn [axis] (- (e/axis-value c axis) (e/axis-value origin axis))) axes)
                            :cell (e/cell-at (:doc @app) c)}) coords)]
       (swap! app assoc :clipboard {:cells entries :shape (mapv (fn [axis] (if (zero? axis) 1 (nth shape (dec axis)))) axes)}
              :anchor nil :mode :normal)
       (notify! (str "Copied " (count coords) " cell(s); p to paste.")))))

(defn paste! []
  (when (editable?)
    (guard!
      #(if-let [{:keys [cells shape]} (:clipboard @app)]
         (let [c (coord) named? (e/named? c)
               axes (if named? [] (clipboard-axes (get-in @app [:doc :dimensions])
                                                (if (= :cube (:view @app)) (:cube-axes @app) (mapping))))]
           (when (some (fn [[index size]] (and (> size 1) (zero? (get axes index 0))))
                       (map-indexed vector shape))
             (e/fail "This selection won't fit in the target dimensions or named cell."))
           (change! (fn [doc]
                      (reduce (fn [d {:keys [offset cell]}]
                                (let [target (if named? c
                                               (reduce (fn [at [index axis]]
                                                         (e/set-axis at axis (+ (e/axis-value c axis) (get offset index 0))))
                                                       c (map-indexed vector axes)))]
                                  (e/put-cell d target cell))) doc cells)))
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
  (swap! app assoc :doc doc :route :editor :anchor nil :mode :normal :named-focus nil
         :editor nil :rule-editor nil :dialog nil :command nil :undo [] :redo [] :view :plane
         :cube-axes [1 2 3] :cube-fit false :cube-initialized false :css-draft (:css doc))
  (let [[x y] (mapping)]
    (swap! app assoc :viewport [(dec (e/axis-value (coord) x)) (dec (e/axis-value (coord) y))]))
  (ensure-visible!))

(defn open-color-example! []
  (open-document! (demo/color-document))
  (swap! app assoc :viewport [0 0] :panel :rules))

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

(defn toggle-theme! [] (swap! app update :theme #(if (= % "dark") "light" "dark")))
(defn toggle-3d! []
  (when (>= (get-in @app [:doc :dimensions]) 3)
    (if (and (= :plane (:view @app)) (some zero? (mapping)))
      (notify! "Choose two non-null axes before entering 3D.")
      (do (swap! app update :view #(if (= % :plane) :cube :plane))
          (swap! app assoc :named-focus nil :editor nil)
          (sync-cube!)
          (when (and (= :cube (:view @app)) (or (:cube-fit @app) (not (:cube-initialized @app))))
            (swap! app assoc :cube-initialized true)
            (when (e/active-bounds (:doc @app)) (fit-cube!)))))))

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
  (let [key (.-key event) ctrl (or (.-ctrlKey event) (.-metaKey event)) shift (.-shiftKey event)
        s @app]
    (cond
      (= key "Escape")
      (do (.preventDefault event)
          (swap! app assoc :editor nil :rule-editor nil :dialog nil :command nil :anchor nil :mode :normal)
          (when (typing-target? event) (.blur (.-target event))))

      (or (:editor s) (:rule-editor s) (:dialog s)) nil
      (typing-target? event) nil
      (and (= "BUTTON" (.. event -target -tagName)) (#{"Enter" " "} key)) nil
      (.-altKey event) nil
      (= key "?") (do (.preventDefault event) (swap! app update :help not))
      (and (= :editor (:route s)) (= :cube (:view s)) (#{"PageUp" "PageDown"} key))
      (do (.preventDefault event) (move-depth! (if (= key "PageUp") 1 -1)))
      (not= :editor (:route s)) nil
      (:command s) (do (.preventDefault event) (command-key! key))
      (and ctrl (= (str/lower-case key) "s")) (do (.preventDefault event) (notify! "Changes save automatically to IndexedDB."))
      (and ctrl (= (str/lower-case key) "z")) (do (.preventDefault event) (undo! shift))
      (and ctrl (= (str/lower-case key) "y")) (do (.preventDefault event) (undo! true))
      (and ctrl (= (str/lower-case key) "v")) (do (.preventDefault event) (toggle-visual!))
      ctrl nil
      (re-matches #"[0-9]" key) (do (.preventDefault event) (switch! (js/Number key)))
      :else
      (when (#{"ArrowLeft" "ArrowRight" "ArrowUp" "ArrowDown" "h" "j" "k" "l" "Tab"
               "Enter" "i" "f" "v" "y" "p" "u" "Delete" "Backspace" "g" "G" "b" "B" "e" "E" "d" "D" "n" "c" "t"} key)
        (.preventDefault event)
        (case key
          ("ArrowLeft" "h") (move! -1 0 shift)
          ("ArrowRight" "l") (move! 1 0 shift)
          ("ArrowUp" "k") (move! 0 -1 shift)
          ("ArrowDown" "j") (move! 0 1 shift)
          "Tab" (move! (if shift -1 1) 0 false)
          ("Enter" "i") (open-editor! nil)
          "f" (open-editor! "formula")
          "v" (toggle-visual!)
          "y" (yank!) "p" (paste!) "u" (when (editable?) (undo! false))
          ("Delete" "Backspace") (clear!)
          "g" (swap! app assoc :command {:axis 0 :text ""})
          "G" (swap! app assoc :command {:axis 1 :text ""})
          "b" (jump-bound! 0 false) "B" (jump-bound! 1 false)
          "e" (jump-bound! 0 true) "E" (jump-bound! 1 true)
          "d" (clear-axis! 0) "D" (clear-axis! 1)
          "n" (new-named!) "c" (swap! app assoc :panel :rules)
          "t" (toggle-3d!) nil)))))

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
                         (not= (:cube-options old) (:cube-options new)))
                 (.catch (db/save-preferences! {:theme (:theme new) :cube-options (cube-options)}) report!)))))

(defn init! []
  (-> (db/open!)
      (.then (fn [_] (db/preferences!)))
      (.then (fn [prefs]
               (when (#{"dark" "light"} (:theme prefs)) (swap! app assoc :theme (:theme prefs)))
               (swap! app assoc :cube-options (preview/restore-options (:cube-options prefs)))
               (install-persistence!)
               (db/all-documents!)))
      (.then (fn [docs]
               (swap! app assoc :documents docs)
               (if (empty? docs) (open-document! (demo/demo-document))
                 (swap! app assoc :route :home :save-status "Saved locally"))))
      (.catch (fn [err] (report! err) (swap! app assoc :route :home :save-status "Storage unavailable")))))

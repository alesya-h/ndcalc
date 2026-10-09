(ns ndcalc.routing)

(defn table-hash [id] (str "#/table/" (js/encodeURIComponent id)))
(defn table-id [hash]
  (when-let [[_ encoded] (re-matches #"#/table/([^/]+)" (or hash ""))]
    (try (let [id (js/decodeURIComponent encoded)] (when (seq id) id))
         (catch :default _ nil))))
(defn current-id []
  (when (exists? js/window) (table-id (.. js/window -location -hash))))
(defn navigate! [id replace?]
  ;; A fragment works on static servers and never requests document content.
  (when (exists? js/window)
    (let [location (.-location js/window) hash (if id (table-hash id) "")]
      (when-not (= hash (.-hash location))
        (let [url (str (.-pathname location) (.-search location) hash)]
          (if replace? (.replaceState (.-history js/window) nil "" url)
            (.pushState (.-history js/window) nil "" url)))))))
(defonce listener (atom nil))
(defn install! [handler]
  (when (exists? js/window)
    (when-let [previous @listener]
      (.removeEventListener js/window "popstate" previous)
      (.removeEventListener js/window "hashchange" previous))
    (.addEventListener js/window "popstate" handler)
    (.addEventListener js/window "hashchange" handler)
    (reset! listener handler)))

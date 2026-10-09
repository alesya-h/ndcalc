(ns ndcalc.routing-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [ndcalc.routing :as routing]
            [ndcalc.state :as s]
            [ndcalc.demo :as demo]))

(use-fixtures :each
  {:before #(remove-watch s/app :persist)
   :after #(when @s/toast-timer (js/clearTimeout @s/toast-timer))})

(deftest table-fragments-round-trip-without-interpreting-ids
  (doseq [id ["01234567-89ab-cdef" "spaces / # ? % ☺" "__proto__" "constructor"]]
    (is (= id (routing/table-id (routing/table-hash id)))))
  (doseq [hash [nil "" "#" "#/" "#unrelated" "#/table/" "#/table/a/b" "#/table/%E0%A4" "#/table/%xx"]]
    (is (nil? (routing/table-id hash)))))

(deftest restoring-only-opens-existing-local-documents
  (let [a (demo/blank-document "first" 3) b (demo/blank-document "second" 2)
        a (assoc-in a [:view :coord] [2 -3 4])]
    (s/restore-document-route! [a b] (:id a))
    (is (= :editor (:route @s/app)))
    (is (= (:id a) (get-in @s/app [:doc :id])))
    (is (= [2 -3 4] (s/numeric-coord)))
    (s/restore-document-route! [a b] (:id b))
    (is (= (:id b) (get-in @s/app [:doc :id])))
    (s/restore-document-route! [a b] nil)
    (is (= :home (:route @s/app)))
    (s/restore-document-route! [a b] "missing")
    (is (= :home (:route @s/app)))
    (is (= [a b] (:documents @s/app)))
    (is (= "This table isn't stored in this browser." (:toast @s/app)))
    (s/restore-document-route! [] "missing")
    (is (= :home (:route @s/app)))
    (is (empty? (:documents @s/app)))))

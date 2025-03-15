(ns tests
  (:require
    ["fs" :as fs]
    ["path" :as path]
    ["process" :refer [env]]
    [clojure.test :refer [deftest async is] :as t]
    [promesa.core :as p]
    [applied-science.js-interop :as j]))


(t/use-fixtures
  :once
  {:before
   #(async
      done
      (p/let [_ nil]
        ; set up stuff
        (done)))
   :after
   #(async
      done
      (p/let [_ nil]
        ; tear down stuff
        (done)))})

(t/use-fixtures
  :each
  {:after
   #(async done
           (p/let [_ nil]
             ; each test
             (done)))})

(deftest first-test
  (t/testing "First basic server tests"
    (async
      done
      (p/let [_ nil]
        ; test runs here
        ))))

(defmethod t/report [:cljs.test/default :begin-test-var] [m]
  (println "TEST ===>" (-> m :var meta :name)))

(defn print-summary []
  (t/report (assoc (:report-counters (t/get-current-env)) :type :summary)))

(defmethod t/report [:cljs.test/default :end-test-vars] [_]
  (let [env (t/get-current-env)
        counters (:report-counters env)
        failures (:fail counters)
        errors (:error counters)]
    (when (or (pos? failures)
              (pos? errors))
      (set! (.-exitCode js/process) 1))
    (print-summary)))

; get all the tests in this file
(defn get-test-vars []
  (->> (ns-publics 'e2e)
       vals
       (filter (comp :test meta))))

(def test-list nil)

#_ (def test-list [#'tests/sometest
                   #'tests/sometest-2])

(if test-list
  (t/test-vars test-list)
  (t/run-tests 'tests))

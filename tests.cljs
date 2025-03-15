(ns tests
  (:require
    ["fs" :as fs]
    ["path" :as path]
    ["process" :refer [env]]
    ["http" :as http]
    ["child_process" :as child-process]
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

(defn wait-for-server-ready [port timeout-ms]
  (let [start-time (js/Date.now)]
    (p/create
      (fn [resolve reject]
        (letfn [(check-server []
                  (let [req (http/get (str "http://localhost:" port "/")
                                     (fn [_] 
                                       (resolve true)))]
                    (.on req "error" 
                         (fn [_]
                           (if (> (- (js/Date.now) start-time) timeout-ms)
                             (reject (js/Error. "Server startup timed out"))
                             (js/setTimeout check-server 100))))))]
          (check-server))))))

(deftest first-test
  (t/testing "First basic server tests"
    (async
      done
      (p/let [; Start josh server as a subprocess
              server-process (child-process/spawn 
                               "npx" 
                               #js ["nbb" "josh.cljs" "--dir" "example" "--port" "8123"]
                               #js {:stdio "inherit"})
              _ (js/console.log "Started josh server subprocess")
              
              ; Wait for server to be ready (max 5 seconds)
              _ (wait-for-server-ready 8123 5000)
              
              ; Make a request to the homepage
              response (p/create
                         (fn [resolve _reject]
                           (-> (http/get "http://localhost:8123/" 
                                        (fn [res]
                                          (let [data (atom "")]
                                            (.on res "data" #(swap! data str %))
                                            (.on res "end" #(resolve @data)))))
                               (.on "error" #(js/console.error "Request error:" %)))))]
        
        ; Check that the response contains expected content
        (is (string? response) "Response should be a string")
        (is (.includes response "<html") "Response should contain HTML")
        (is (.includes response "scittle") "Response should contain scittle")
        
        ; Clean up - kill the server process
        (when server-process
          (.kill server-process)
          (js/console.log "Killed josh server subprocess"))
        
        (done)))))

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

(ns tests
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
    ["fs" :as fs]
    ["path" :as path]
    ["process" :refer [env]]
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

;; Server test utilities
(defn wait-for-server-ready 
  "Poll a server endpoint until it responds or times out"
  [port timeout-ms]
  (let [start-time (js/Date.now)]
    (p/create
      (fn [resolve reject]
        (letfn [(check-server []
                  (-> (js/fetch (str "http://localhost:" port "/"))
                      (.then #(resolve true))
                      (.catch 
                        (fn [_]
                          (if (> (- (js/Date.now) start-time) timeout-ms)
                            (reject (js/Error. "Server startup timed out"))
                            (js/setTimeout check-server 100))))))]
          (check-server))))))

(defn start-josh-server 
  "Start a josh server in a subprocess with the given options"
  [{:keys [dir port]
    :or {dir "./" port 8123}}]
  (p/let [server-process (child-process/spawn 
                           "npx" 
                           #js ["nbb" "josh.cljs" 
                                "--dir" dir 
                                "--port" (str port)]
                           #js {:stdio "inherit"})]
    (js/console.log (str "Started josh server subprocess on port " port))
    ; Wait for server to be ready (max 5 seconds)
    (wait-for-server-ready port 5000)
    server-process))

(defn stop-josh-server 
  "Stop a running josh server process"
  [server-process]
  (when server-process
    (.kill server-process)
    (js/console.log "Killed josh server subprocess")))

(deftest first-test
  (t/testing "First basic server tests"
    (async
      done
      (p/let [server-process (start-josh-server {:dir "example" :port 8123})
              res (js/fetch "http://localhost:8123/")
              response (.text res)]
        
        (is (string? response) "Response should be a string")
        (is (.includes response "<html") "Response should contain HTML")
        (is (.includes response "scittle") "Response should contain scittle")
        
        (stop-josh-server server-process)
        
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

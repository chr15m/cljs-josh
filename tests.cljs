(ns tests
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
    ["fs" :as fs]
    ["path" :as path]
    ["process" :refer [env]]
    ["child_process" :as child-process]
    [clojure.test :refer [deftest async is] :as t]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    [clojure.tools.cli :as cli]))

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

(def cli-options
  [["-d" "--dir DIR" "Path to dir to serve."
    :default "./"]
   ["-p" "--port PORT" "Webserver port number."
    :default 8123
    :parse-fn js/Number]
   [nil "--prod" "Run in production mode"]])

(defn start-josh-server 
  "Start a josh server in a subprocess with the given options"
  [args env-vars]
  (let [parsed-opts (:options (cli/parse-opts args cli-options))
        port (:port parsed-opts)
        ; Create a proper environment by preserving the current process.env
        ; and merging in any custom environment variables
        current-env (js/Object.assign #js {} (.-env js/process))
        env-obj (if env-vars
                  (js/Object.assign current-env (clj->js env-vars))
                  current-env)
        cmd-args (clj->js (concat ["nbb" "josh.cljs"] args))
        server-process (child-process/spawn 
                         "npx" 
                         cmd-args
                         #js {:stdio "inherit"
                              :env env-obj})]
    (js/console.log (str "Started josh server subprocess with args: " 
                         (js/JSON.stringify cmd-args)
                         (when (and env-vars (.-NODE_ENV env-vars))
                           (str " with NODE_ENV=" (.-NODE_ENV env-vars)))))
    ; Wait for server to be ready (max 5 seconds)
    (p/let [_ (wait-for-server-ready port 5000)]
      server-process)))

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
      (p/let [server-process (start-josh-server ["--dir" "example" "--port" "8123"] nil)
              res (js/fetch "http://localhost:8123/")
              response (.text res)]
        
        (is (string? response) "Response should be a string")
        (is (.includes response "<html") "Response should contain HTML")
        (is (.includes response "scittle") "Response should contain scittle")
        
        (stop-josh-server server-process)
        
        (done)))))

(deftest html-injection-test
  (t/testing "HTML loader script injection"
    (async
      done
      (p/let [server-process (start-josh-server ["--dir" "example" "--port" "8124"] nil)
              res (js/fetch "http://localhost:8124/")
              html-content (.text res)]
        
        ; Check that the loader script is injected
        (is (.includes html-content "_josh-reloader") 
            "Response should contain the loader script")
        (is (.includes html-content "setup-sse-connection") 
            "Response should contain the SSE connection setup")
        (is (.includes html-content "reload-scittle-tags") 
            "Response should contain the CLJS reload function")
        (is (.includes html-content "reload-css-tags") 
            "Response should contain the CSS reload function")
        
        (stop-josh-server server-process)
        
        (done)))))

(deftest prod-flag-test
  (t/testing "Production mode with --prod flag"
    (async
      done
      (p/let [server-process (start-josh-server ["--dir" "example" "--port" "8125" "--prod"] nil)
              res (js/fetch "http://localhost:8125/")
              html-content (.text res)]
        
        ; Check that the loader script is NOT injected
        (is (not (.includes html-content "_josh-reloader")) 
            "Response should NOT contain the loader script")
        (is (not (.includes html-content "setup-sse-connection")) 
            "Response should NOT contain the SSE connection setup")
        
        (stop-josh-server server-process)
        
        (done)))))

(deftest prod-env-test
  (t/testing "Production mode with NODE_ENV=production"
    (async
      done
      (p/let [server-process (start-josh-server 
                               ["--dir" "example" "--port" "8126"]
                               {"NODE_ENV" "production"})
              res (js/fetch "http://localhost:8126/")
              html-content (.text res)]
        
        ; Check that the loader script is NOT injected
        (is (not (.includes html-content "_josh-reloader")) 
            "Response should NOT contain the loader script")
        (is (not (.includes html-content "setup-sse-connection")) 
            "Response should NOT contain the SSE connection setup")
        
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

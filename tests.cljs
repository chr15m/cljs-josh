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

;; Helper functions for file manipulation in tests
(defn create-test-file
  "Create a file with the given content for testing"
  [filepath content]
  (p/let [_ (p/create (fn [resolve reject]
                        (fs/mkdir (path/dirname filepath) #js {:recursive true}
                                 (fn [err]
                                   (if err
                                     (reject err)
                                     (resolve true))))))
          _ (p/create (fn [resolve reject]
                        (fs/writeFile filepath content
                                     (fn [err]
                                       (if err
                                         (reject err)
                                         (resolve true))))))]
    filepath))

(defn delete-test-file
  "Delete a test file"
  [filepath]
  (p/catch
    (p/create (fn [resolve reject]
                (fs/unlink filepath
                          (fn [err]
                            (if err
                              (reject err)
                              (resolve true))))))
    (fn [_] false)))

(defn delete-test-dir
  "Delete a test directory recursively"
  [dirpath]
  (p/catch
    (p/create (fn [resolve reject]
                (fs/rm dirpath #js {:recursive true, :force true}
                      (fn [err]
                        (if err
                          (reject err)
                          (resolve true))))))
    (fn [_] false)))

(deftest path-resolution-test
  (t/testing "Path resolution for different URL patterns"
    (async
      done
      (p/let [;; Create test directory structure
              test-dir "example/test-paths"
              _ (p/create (fn [resolve reject]
                            (fs/mkdir test-dir #js {:recursive true}
                                     (fn [err]
                                       (if err
                                         (reject err)
                                         (resolve true))))))

              ;; Create various test files
              root-html (create-test-file
                          (path/join test-dir "root.html")
                          "<html><body><h1>Root HTML</h1></body></html>")

              subdir (path/join test-dir "subdir")
              _ (p/create (fn [resolve reject]
                            (fs/mkdir subdir #js {:recursive true}
                                     (fn [err]
                                       (if err
                                         (reject err)
                                         (resolve true))))))

              subdir-html (create-test-file
                            (path/join subdir "page.html")
                            "<html><body><h1>Subdir Page</h1></body></html>")

              index-html (create-test-file
                           (path/join subdir "index.html")
                           "<html><body><h1>Subdir Index</h1></body></html>")

              ;; Start server
              server-process (start-josh-server
                               ["--dir" "example" "--port" "8127"]
                               nil)

              ;; Test direct HTML file access
              _ (js/console.log "Testing direct HTML file access")
              root-res (js/fetch "http://localhost:8127/test-paths/root.html")
              root-content (.text root-res)
              _ (js/console.log "Direct HTML file access test completed")

              ;; Test HTML file in subdirectory
              _ (js/console.log "Testing HTML file in subdirectory")
              subdir-res (js/fetch "http://localhost:8127/test-paths/subdir/page.html")
              subdir-content (.text subdir-res)
              _ (js/console.log "Subdirectory HTML file test completed")

              ;; Test implicit index.html resolution
              _ (js/console.log "Testing implicit index.html resolution with trailing slash")
              index-res (js/fetch "http://localhost:8127/test-paths/subdir/")
              index-content (.text index-res)
              _ (js/console.log "Implicit index.html with trailing slash test completed")

              ;; Test path without trailing slash (should resolve to index.html)
              _ (js/console.log "Testing implicit index.html resolution without trailing slash")
              no-slash-res (js/fetch "http://localhost:8127/test-paths/subdir")
              no-slash-content (.text no-slash-res)
              _ (js/console.log "Implicit index.html without trailing slash test completed")

              ;; Test non-existent path
              _ (js/console.log "Testing non-existent path")
              not-found-res (p/catch
                              (js/fetch "http://localhost:8127/test-paths/not-exists.html")
                              (fn [e]
                                (js/console.log "Non-existent path error caught as expected")
                                e))
              not-found-status (if (instance? js/Error not-found-res)
                                 404  ; Assume 404 if we got an error
                                 (.-status not-found-res))
              _ (js/console.log "Non-existent path test completed with status:" not-found-status)

              ;; Test root path without extension
              _ (js/console.log "Testing root path without extension")
              root-no-ext-res (js/fetch "http://localhost:8127/test-paths/root")
              root-no-ext-content (.text root-no-ext-res)
              _ (js/console.log "Root path without extension test completed")

              ;; Test root path with trailing slash
              _ (js/console.log "Testing root path with trailing slash")
              root-slash-res (js/fetch "http://localhost:8127/test-paths/root/")
              root-slash-content (.text root-slash-res)
              _ (js/console.log "Root path with trailing slash test completed")

              ;; Test top-level root path
              _ (js/console.log "Testing top-level root path")
              top-root-res (js/fetch "http://localhost:8127/")
              top-root-content (.text top-root-res)
              _ (js/console.log "Top-level root path test completed")]

        ;; Verify direct HTML file access
        (is (.includes root-content "Root HTML")
            "Should serve direct HTML file")

        ;; Verify HTML file in subdirectory
        (is (.includes subdir-content "Subdir Page")
            "Should serve HTML file in subdirectory")

        ;; Verify implicit index.html resolution with trailing slash
        (is (.includes index-content "Subdir Index")
            "Should serve index.html for directory path with trailing slash")

        ;; Verify implicit index.html resolution without trailing slash
        (is (.includes no-slash-content "Subdir Index")
            "Should serve index.html for directory path without trailing slash")

        ;; Verify root path without extension resolves to root.html
        (is (.includes root-no-ext-content "Root HTML")
            "Should serve root.html for /root path without extension")

        ;; Verify root path with trailing slash resolves to root.html
        (is (.includes root-slash-content "Root HTML")
            "Should serve root.html for /root/ path with trailing slash")

        ;; Verify top-level root path resolves to index.html
        (is (.includes top-root-content "Scittle Example")
            "Should serve index.html for top-level root path")

        ;; Verify non-existent path returns 404 or error
        (is (or (= not-found-status 404)
                (instance? js/Error not-found-res))
            "Should return 404 or error for non-existent path")

        ;; Clean up test files and stop server
        (delete-test-dir test-dir)
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

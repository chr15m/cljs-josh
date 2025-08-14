#!/usr/bin/env -S npx nbb --classpath ./node_modules/josh/
(ns josh
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    ["net" :as net]
    ["os" :as os]
    ["path" :as path]
    ["fs/promises" :as fs]
    ["fs" :as fs-sync]
    ["process" :refer [argv cwd env]]
    [applied-science.js-interop :as j]
    [promesa.core :as p]
    ["node-watch$default" :as watch]
    ["express$default" :as express]
    ["bencode$default" :as bencode]
    ["ws" :refer [WebSocketServer]]
    [nbb.core :refer [load-file *file*]]))

; tests
; /blah/blah/index.html
; /blah/blah.html
; /blah/blah (implicit index)
; test </BODY> and </body>

(def default-port 8000)
(def scittle-tag-re
  #"(?i)<script[^>]+src\s*=\s*['\"]([^'\"]*scittle(?:\.min)?\.js)['\"][^>]*>")

(def debug (j/get env :DEBUG))

(defonce connections (atom #{}))

; *** nREPL functionality *** ;

(defonce nrepl-ws-channel (atom nil))
(defonce nrepl-sessions (atom {}))
(defonce nrepl-sockets (atom #{}))

(defn send-bencode [out response]
  (.write out (bencode/encode response)))

(defn forward-to-browser [socket msg-clj]
  (if-let [ws @nrepl-ws-channel]
    (.send ws (pr-str msg-clj))
    (let [session-id (:session msg-clj)
          id (:id msg-clj)
          err-msg "#error {:message \"No browser connected.\"}"
          ex-resp (cond-> {:ex err-msg :id id}
                    session-id (assoc :session session-id))
          ns-resp (cond-> {:ns "user" :id id}
                    session-id (assoc :session session-id))
          status-resp (cond-> {:status ["done"] :id id :ns ""}
                        session-id (assoc :session session-id))]
      (js/console.error "nREPL: No browser connected, sending error to client.")
      (send-bencode socket (clj->js ex-resp))
      (send-bencode socket (clj->js ns-resp))
      (send-bencode socket (clj->js status-resp)))))

(defn do-eval [socket msg-clj]
  (let [code (:code msg-clj)
        session-id (:session msg-clj)
        id (:id msg-clj)]
    (cond
      (and (not @nrepl-ws-channel) (= code "*ns*"))
      (do
        (js/console.log
          "nREPL: Intercepted *ns* eval with no browser, returning cljs.user.")
        (send-bencode socket
                      (clj->js (cond-> {:value "\"cljs.user\"" :id id}
                                 session-id (assoc :session session-id))))
        (send-bencode socket
                      (clj->js (cond-> {:ns "cljs.user" :id id}
                                 session-id (assoc :session session-id))))
        (send-bencode socket
                      (clj->js (cond-> {:status ["done"] :id id}
                                 session-id (assoc :session session-id)))))

      (and code (or (str/includes? code "clojure.main/repl-requires")
                    (str/includes? code "System/getProperty")))
      (do
        (js/console.log
          "nREPL: Intercepted clojure.main/repl-requires or System/getProperty")
        (send-bencode socket
                      (clj->js (cond-> {:value "nil" :id id}
                                 session-id (assoc :session session-id))))
        (send-bencode socket
                      (clj->js (cond-> {:status ["done"] :id id}
                                 session-id (assoc :session session-id)))))

      :else
      (forward-to-browser socket (assoc msg-clj :op :eval)))))

(defn handle-nrepl-message [socket msg]
  (let [msg-clj (js->clj msg :keywordize-keys true)
        op (keyword (:op msg-clj))]
    ;(print msg-clj)
    (case op
      :clone (let [session-id (str (random-uuid))
                   response (clj->js (cond-> {:new-session session-id
                                              :status ["done"]
                                              :id (:id msg-clj)}
                                       (:session msg-clj)
                                       (assoc :session (:session msg-clj))))]
               (swap! nrepl-sessions assoc session-id socket)
               (send-bencode socket response))

      :describe (let [node-version (j/get-in js/process [:versions :node])
                      [major minor incremental] (str/split node-version #"\.")
                      response
                      (clj->js
                        (cond->
                          {:versions {"scittle" {"major" "TODO"
                                                 "version-string" "TODO"}
                                      "cljs-josh" {"major" "TODO"
                                                   "version-string" "TODO"}
                                      "node" {"major" (str "v" major)
                                              "minor" minor
                                              "incremental" incremental
                                              "version-string"
                                              (str "v" node-version)}}
                           :ops (zipmap
                                  (map name [:classpath
                                             :clone
                                             :close
                                             :complete
                                             :describe
                                             :eldo
                                             :eval
                                             :info
                                             :load-file
                                             :lookup
                                             :macroexpand])
                                  (repeat {}))
                           :aux {}
                           :status ["done"]
                           :id (:id msg-clj)}
                          (:session msg-clj)
                          (assoc :session (:session msg-clj))))]
                  (send-bencode socket response))

      :eval (do-eval socket msg-clj)

      :load-file (do-eval socket (assoc msg-clj :code (:file msg-clj)))

      :classpath (let [response (clj->js
                                  (cond-> {:classpath []
                                           :status ["done"]
                                           :id (:id msg-clj)}
                                    (:session msg-clj)
                                    (assoc :session (:session msg-clj))))]
                   (send-bencode socket response))

      :close (let [{:keys [session id]} msg-clj]
               (when session
                 (swap! nrepl-sessions dissoc session))
               (let [response (clj->js
                                (cond-> {:status ["done" "session-closed"]
                                         :id id}
                                  session (assoc :session session)))]
                 (send-bencode socket response)))

      :macroexpand (forward-to-browser socket msg-clj)

      (forward-to-browser socket msg-clj))))

(defn handle-nrepl-client [socket]
  (swap! nrepl-sockets conj socket)
  (.on socket "close" (fn []
                        (js/console.log "nREPL client disconnected.")
                        (swap! nrepl-sockets disj socket)
                        (let [to-remove (->> @nrepl-sessions
                                             (filter (fn [[_v s]] (= s socket)))
                                             (map first))]
                          (swap! nrepl-sessions #(apply dissoc % to-remove)))))
  (let [buffer (atom (js/Buffer.alloc 0))]
    (.on socket "data"
         (fn [data]
           (swap! buffer #(js/Buffer.concat (clj->js [% data])))
           (loop []
             (when (> (.-length @buffer) 0)
               (let [msg (try
                           (bencode/decode @buffer "utf8")
                           (catch js/Error e
                             (when-not
                               (or (str/includes? (.-message e)
                                                  "Unexpected end of buffer")
                                   (str/includes? (.-message e)
                                                  "Not a string")
                                   (str/includes? (.-message e)
                                                  "Invalid data"))
                               (js/console.error
                                 "nREPL stream error, closing connection:" e)
                               (.end socket))
                             :decode-error))]
                 (when debug (js/console.error "<=" msg))
                 (when (not= msg :decode-error)
                   (let [bytes-consumed (bencode/encodingLength msg)]
                     (handle-nrepl-message socket msg)
                     (swap! buffer #(.slice % bytes-consumed))
                     (recur))))))))))

(defn start-nrepl-server! [port host]
  (let [server (net/createServer #(handle-nrepl-client %))]
    (.listen server port host
             (fn []
               (let [port-file-path (path/join (cwd) ".nrepl-port")]
                 (fs/writeFile port-file-path (str port)))
               (js/console.log
                 (str "nREPL server started on port " port
                      " on host " host " - nrepl://" host ":" port))))))

(defn start-ws-server! [port]
  (let [ws-server (WebSocketServer. (clj->js {:port port}))]
    (.on ws-server "connection"
         (fn [ws]
           (js/console.log "nREPL browser connected.")
           (reset! nrepl-ws-channel ws)
           (.on ws "message"
                (fn [message]
                  (let [response (edn/read-string (.toString message))
                        session-id (:session response)
                        socket (or (get @nrepl-sessions session-id)
                                   (when (and (not session-id)
                                              (= 1 (count @nrepl-sockets)))
                                     (first @nrepl-sockets)))]
                    (when debug (js/console.log "=>" (pr-str response)))
                    (if socket
                      (send-bencode socket (clj->js response))
                      (js/console.error
                        "nREPL: No socket for session" session-id)))))
           (.on ws "close" #(do (js/console.log "nREPL browser disconnected.")
                                (reset! nrepl-ws-channel nil)))))))

; *** webserver funtionality *** ;

(defn get-local-ip-addresses []
  (let [interfaces (os/networkInterfaces)]
    (for [[_ infos] (js/Object.entries interfaces)
          info infos
          :when (= (.-family info) "IPv4")]
      (.-address info))))

(defn try-file [html-path]
  (p/catch
    (p/let [file-content (fs/readFile html-path)]
      (when file-content
        (.toString file-content)))
    (fn [_err] nil))) ; couldn't load HTML at this path

(defn find-html [req dir]
  (let [base-path (path/join dir (j/get req :path))
        extension (.toLowerCase (path/extname base-path))]
    (when (or (= extension "")
              (= extension ".htm")
              (= extension ".html"))
      (p/let [html (try-file base-path)
              html (or html (try-file (str base-path ".html")))
              html (or html (try-file (path/join base-path "index.html")))]
        html))))

(defn sse-handler
  [req res]
  (js/console.log "SSE connection established")
  (j/call res :setHeader "Content-Type" "text/event-stream")
  (j/call res :setHeader "Cache-Control" "no-cache")
  (j/call res :setHeader "Connection" "keep-alive")
  (j/call res :flushHeaders)
  (j/call req :on "close"
          (fn []
            (js/console.log "SSE connection closed")
            (swap! connections disj res)
            (j/call res :end)))
  (swap! connections conj res)
  (j/call res :write (str "data: "
                          (js/JSON.stringify
                            (clj->js {:hello 42}))
                          "\n\n")))

(defn send-to-all [msg]
  (doseq [res @connections]
    (j/call res :write (str "data: "
                            (js/JSON.stringify
                              (clj->js msg))
                            "\n\n"))))


(defn get-free-port []
  (js/Promise.
    (fn [res]
      (let [server (net/createServer)]
        (.once server "listening"
               (fn []
                 (let [port (j/get (.address server) "port")]
                   (.close server #(res port)))))
        (.listen server 0)))))

(defn read-nrepl-config []
  (let [local-config ".nrepl.edn"
        home (os/homedir)
        global-config (when home (path/join home ".nrepl" "nrepl.edn"))
        config-path (if (fs-sync/existsSync local-config)
                      local-config
                      (when (and global-config
                                 (fs-sync/existsSync global-config))
                        global-config))]
    (when config-path
      (try
        (-> config-path
            fs-sync/readFileSync
            .toString
            edn/read-string)
        (catch js/Error e
          (js/console.error "Error reading" config-path e)
          nil)))))

(def loader
  '(defonce _josh-reloader
     (do
       (js/console.log "Josh Scittle re-loader installed")

       (defn match-tags [tags source-attribute file-path]
         (.filter (js/Array.from tags)
                  #(let [src (aget % source-attribute)
                         url (when (seq src) (js/URL. src))
                         path (when url (aget url "pathname"))]
                     (= file-path path))))

       (defn reload-scittle-tags [file-path]
         (let [scittle-tags
               (.querySelectorAll
                 js/document
                 "script[type='application/x-scittle']")
               matching-scittle-tags
               (match-tags scittle-tags "src" file-path)]
           (doseq [tag matching-scittle-tags]
             (js/console.log "Reloading" (aget tag "src"))
             (-> js/scittle
                 .-core
                 (.eval_script_tags tag)))))

       (defn reload-css-tags [file-path]
         (let [css-tags
               (.querySelectorAll
                 js/document
                 "link[rel='stylesheet']")
               matching-css-tags
               (match-tags css-tags "href" file-path)]
           (doseq [tag matching-css-tags]
             (js/console.log "Reloading" (aget tag "href"))
             (aset tag "href"
                   (-> (aget tag "href")
                       (.split "?")
                       first
                       (str "?" (.getTime (js/Date.))))))))

       (defn setup-sse-connection []
         (let [conn (js/EventSource. "/_cljs-josh")]
           (aset conn "onerror"
                 (fn [ev]
                   (js/console.error "SSE connection closed.")
                   (when (= (aget conn "readyState")
                            (aget js/EventSource "CLOSED"))
                     (js/console.error "Creating new SSE connection.")
                     (js/setTimeout
                       #(setup-sse-connection)
                       2000))))
           (aset conn "onmessage"
                 (fn [data]
                   (let [packet (-> data
                                    (aget "data")
                                    js/JSON.parse
                                    (js->clj :keywordize-keys true))]
                     ; (js/console.log "packet" (pr-str packet))
                     (when-let [file-path (:reload packet)]
                       (cond (.endsWith file-path ".cljs")
                             (reload-scittle-tags file-path)
                             (.endsWith file-path ".css")
                             (reload-css-tags file-path))))))))

       (setup-sse-connection))))

(defn html-injector [req res done dir ws-port]
  ; intercept static requests to html and inject the loader script
  (p/let [html (find-html req dir)]
    (if html
      (let [scittle-tag-match
            (re-find scittle-tag-re html)
            scittle-js-url (when scittle-tag-match (second scittle-tag-match))
            nrepl-scripts
            (when scittle-js-url
              (str
                "<script>var SCITTLE_NREPL_WEBSOCKET_PORT = "
                ws-port ";</script>"
                "<script src=\"" (str/replace scittle-js-url "scittle."
                                              "scittle.nrepl.")
                "\" type=\"application/javascript\"></script>"))
            loader-script (str "<script type='application/x-scittle'>"
                               (pr-str loader) "</script>")
            injected-html
            (.replace html #"(?i)</body>"
                      (str nrepl-scripts loader-script "</body>"))]
        (.send res injected-html))
      (done))))

(defn frontend-file-changed
  [_event-type filename]
  (js/console.log "Frontend reloading:" filename)
  (send-to-all {:reload (str "/" filename)}))

(def cli-options
  [["-d" "--dir DIR" "Path to dir to serve."
    :default "./"
    :validate [#(fs-sync/existsSync %) "Must be a directory that exists."]]
   ["-p" "--port PORT" "Webserver port number."
    :default default-port
    :parse-fn js/Number
    :validate [#(< 1024 % 0x10000) "Must be a number between 1024 and 65536"]]
   ["-i" "--init" (str "Set up a basic Scittle project. Copies an html,"
                       "cljs, and css file into the current folder.")]
   ["-h" "--help"]
   [nil "--prod" "Disable live-reloading and nREPL for production."]])

(defonce handle-error
  (.on js/process "uncaughtException"
       (fn [error]
         (js/console.error error))))

(defn print-usage [summary]
  (print "Program options:")
  (print summary))

(defn install-examples []
  (let [josh-dir (path/dirname *file*)
        example-dir (path/join josh-dir "example")
        files ["index.html" "main.cljs" "style.css"]]
    (js/console.log "Copying example files here.")
    (p/do!
      (->> files
           (map #(p/catch
                   (p/do!
                     (fs/access %)
                     (js/console.log % "exists already, skipping."))
                   (fn [_err]
                     (js/console.log "Copying" %)
                     (fs/cp (path/join example-dir %) %))))
           (p/all))
      (js/console.log "Now run josh to serve this folder."))))

(defn spath->posix
  "Converts SPATH to a POSIX-style path with '/' separators and returns it."
  [spath]
  (if (= os/path "/")
    spath
    (.join (.split spath path/sep) "/")))

(defn start-watchers [dir]
  ; watch this server itself
  (when *file*
    (watch #js [*file*]
           (fn [_event-type filename]
             (js/console.log "Reloading" filename)
             (load-file filename))))
  ; watch served frontend files
  (watch dir
         #js {:filter
              (fn [f]
                (or (.endsWith f ".css")
                    (and
                      (.endsWith f ".cljs")
                      (try
                        (fs-sync/accessSync
                          f fs-sync/constants.R_OK)
                        true
                        (catch :default _e)))))
              :recursive true}
         (fn [event-type filepath]
           (let [filepath-rel (path/relative dir filepath)
                 filepath-posix (spath->posix filepath-rel)]
             (frontend-file-changed event-type filepath-posix)))))

(defn start-webserver [app dir port]
  ; launch the webserver
  (.use app (.static express dir))
  (.listen app port
           (fn []
             (js/console.log (str "Serving " dir
                                  " on port " port ":"))
             (doseq [ip (reverse
                          (sort-by count
                                   (get-local-ip-addresses)))]
               (js/console.log (str "- http://" ip ":" port))))))

(defn run-servers [options]
  (p/catch
    (p/let [port (:port options)
            dir (:dir options)
            prod? (:prod options)
            app (express)]
      (when-not prod?
        (p/let [config (read-nrepl-config)
                nrepl-p (or (get config :port) (get-free-port))
                ws-p (or (get config :ws-port) (get-free-port))]
          (start-nrepl-server! nrepl-p (get config :bind "127.0.0.1"))
          (start-ws-server! ws-p)
          (start-watchers dir)
          (.get app "/*" #(html-injector %1 %2 %3 dir ws-p))
          (.use app "/_cljs-josh" #(sse-handler %1 %2))))
      (start-webserver app dir port))
    #(js/console.error %)))

(defn main
  [& args]
  (let [{:keys [errors options summary]} (cli/parse-opts args cli-options)]
    (cond errors
          (doseq [e errors]
            (print e))
          (:help options)
          (print-usage summary)
          (:init options)
          (install-examples)
          :else
          (run-servers options))))

(defn get-args [argv]
  (if *file*
    (let [argv-vec (mapv
                     #(try (fs-sync/realpathSync %)
                           (catch :default _e %))
                     (js->clj argv))
          script-idx (.indexOf argv-vec *file*)]
      (when (>= script-idx 0)
        (not-empty (subvec argv-vec (inc script-idx)))))
    (not-empty (js->clj (.slice argv
                                (if
                                  (or
                                    (.endsWith
                                      (or (aget argv 1) "")
                                      "node_modules/nbb/cli.js")
                                    (.endsWith
                                      (or (aget argv 1) "")
                                      "/bin/nbb"))
                                  3 2))))))

(defonce started
  (apply main (not-empty (get-args argv))))

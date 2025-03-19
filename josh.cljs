(ns josh
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
    [clojure.tools.cli :as cli]
    ["os" :as os]
    ["path" :as path]
    ["fs/promises" :as fs]
    ["fs" :as fs-sync]
    [applied-science.js-interop :as j]
    [promesa.core :as p]
    ["node-watch$default" :as watch]
    ["express$default" :as express]
    [nbb.core :refer [load-file *file*]]))

; tests
; /blah/blah/index.html
; /blah/blah.html
; /blah/blah (implicit index)
; test </BODY> and </body>

(def default-port 8000)

(defonce connections (atom #{}))

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

(def loader
  '(defonce _josh-reloader
     (do
       (js/console.log "Josh Scittle re-loader installed")

       (defn- match-tags [tags source-attribute file-path]
         (.filter (js/Array.from tags)
                  #(let [src (aget % source-attribute)
                         url (when (seq src) (js/URL. src))
                         path (when url (aget url "pathname"))]
                     (= file-path path))))

       (defn- reload-scittle-tags [file-path]
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

       (defn- reload-css-tags [file-path]
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

       (defn- setup-sse-connection []
         (let [conn (js/EventSource. "/_cljs-josh")]
           (aset conn "onerror"
                 (fn [ev]
                   (js/console.error "SSE connection closed.")
                   (when (= (aget conn "readyState") (aget js/EventSource "CLOSED"))
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

(defn html-injector [req res done dir]
  ; intercept static requests to html and inject the loader script
  (p/let [html (find-html req dir)]
    (if html
      (let [injected-html (.replace html #"(?i)</body>"
                                    (str
                                      "<script type='application/x-scittle'>"
                                      (pr-str loader)
                                      "</script></body>"))]
        ;(js/console.log "Intercepted" (j/get req :path))
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
   ["-i" "--init" "Set up a basic Scittle project. Copies an html, cljs, and css file into the current folder."]
   ["-h" "--help"]])

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
          (let [port (:port options)
                dir (:dir options)]
            ; watch this server itself
            (watch #js [*file*]
                   (fn [_event-type filename]
                     (js/console.log "Reloading" filename)
                     (load-file filename)))
            ; watch served frontend filem
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
                   #(frontend-file-changed %1 %2))
            ; launch the webserver
            (let [app (express)] 
              (.get app "/*" #(html-injector %1 %2 %3 dir))
              (.use app (.static express dir))
              (.use app "/_cljs-josh" #(sse-handler %1 %2))
              (.listen app port
                       (fn []
                         (js/console.log (str "Serving " dir
                                              " on port " port ":"))
                         (doseq [ip (reverse
                                      (sort-by count
                                               (get-local-ip-addresses)))]
                           (js/console.log (str "- http://" ip ":" port))))))))))

(defonce started
  (apply main (not-empty (js->clj (.slice js/process.argv 2)))))

(ns server
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
    [clojure.tools.cli :as cli]
    ["os" :as os]
    ["path" :as path]
    ["fs/promises" :as fs]
    ["process" :as process]
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

(def port 8000)

(def dir (.cwd process))

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

(defn find-html [req]
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
            (j/call res :end)))
  (j/call res :write (str "data: "
                          (js/JSON.stringify
                            (clj->js {:hello 42}))
                          "\n\n")))

(def loader
  '(do
     (js/console.log "Josh Scittle re-loader installed")
     (let [conn (js/EventSource. "/_cljs-josh")]
       (aset conn "onmessage"
             (fn [data]
               (let [packet (-> data
                                (aget "data")
                                js/JSON.parse
                                (js->clj :keywordize-keys true))]
                 (js/console.log "packet" (pr-str packet))))))
     (let [tags "script[type='application/x-scittle'], script[src$='/main.cljs']"])))

(defn html-injector [req res done]
  ; intercept static requests to html and inject the loader script
  (p/let [html (find-html req)]
    (if html
      (let [injected-html (.replace html #"(?i)</body>"
                                    (str
                                      "<script type='application/x-scittle'>"
                                      (pr-str loader)
                                      "</script></body>"))]
        ;(js/console.log "Intercepted" (j/get req :path))
        (.send res injected-html))
      (done))))

(def cli-options
  [["-p" "--port PORT" "Webserver port number."
    :default port
    :parse-fn js/Number
    :validate [#(< 1024 % 0x10000) "Must be a number between 1024 and 65536"]]
   ["-h" "--help"]])

(defonce handle-error
  (.on js/process "uncaughtException"
       (fn [error]
        (js/console.error error))))

(defn print-usage [opts]
  (print "Program options:")
  (print (:summary opts)))

(defn main
  [& args]
  (let [opts (cli/parse-opts args cli-options)]
    (if (-> opts :options :help)
      (print-usage opts)
      (do
        (watch #js [*file*]
               (fn [_event-type filename]
                 (js/console.log "Reloading" filename)
                 (load-file filename)))
        (let [port (-> opts :options :port)
              app (express)]
          (.get app "/*" #(html-injector %1 %2 %3))
          (.use app (.static express dir))
          (.use app "/_cljs-josh" #(sse-handler %1 %2))
          (.listen app port
                   (fn []
                     (js/console.log (str "Web server running on port " port ":"))
                     (doseq [ip (reverse (sort-by count (get-local-ip-addresses)))]
                       (js/console.log (str "- http://" ip ":" port))))))))))

(apply main *command-line-args*)

(ns server
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let}}}
  (:require
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


(def loader
  '(js/alert "goober"))

(defn html-injector [req res done]
  ; intercept static requests to html
  ; read the file from disk
  ; inject a script tag with an alert
  ; return the modified html
  (p/let [html (find-html req)]
    ; (js/console.log "HTML" html)
    (if html
      (let [injected-html (.replace html #"(?i)</body>"
                                    (str
                                      "<script type='application/x-scittle'>"
                                      (pr-str loader)
                                      "</script></body>"))]
        ;(js/console.log "sending" injected-html)
        (.send res injected-html))
      (done))))

(defonce webserver
  (let [app (express)]
    (.get app "/*" #(html-injector %1 %2 %3))
    (.use app (.static express dir))
    (.listen app port
             (fn []
               (js/console.log (str "Web server running on port " port))
               (doseq [ip (reverse (sort-by count (get-local-ip-addresses)))]
                 (js/console.log (str "\thttp://" ip ":" port)))))))

(defonce watcher
  (watch #js [*file*]
         (fn [_event-type filename]
           (js/console.log "Reloading" filename)
           (load-file filename))))

(defonce handle-error
  (.on js/process "uncaughtException"
       (fn [error]
        (js/console.error error))))

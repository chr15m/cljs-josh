(ns nrepl
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    ["bencode$default" :as bencode]
    ["net" :as net]
    ["ws" :refer [WebSocketServer]]
    ["path" :as path]
    ["fs/promises" :as fs]
    ["process" :refer [cwd]]
    [applied-science.js-interop :as j]))

(defonce nrepl-ws-channel (atom nil))
(defonce nrepl-sessions (atom {}))
(defonce nrepl-sockets (atom #{}))
(defonce ws-port (atom nil))

(defn- send-bencode [out response]
  (.write out (bencode/encode response)))

(defn- forward-to-browser [socket msg-clj]
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

(defn- do-eval [socket msg-clj]
  (let [code (:code msg-clj)
        session-id (:session msg-clj)
        id (:id msg-clj)]
    (if (and code (or (str/includes? code "clojure.main/repl-requires")
                      (str/includes? code "System/getProperty")))
      (let [value-resp (cond-> {:value "nil" :id id}
                         session-id (assoc :session session-id))
            status-resp (cond-> {:status ["done"] :id id}
                          session-id (assoc :session session-id))]
        (js/console.log "nREPL: Intercepted clojure.main/repl-requires or System/getProperty")
        (send-bencode socket (clj->js value-resp))
        (send-bencode socket (clj->js status-resp)))
      (forward-to-browser socket (assoc msg-clj :op :eval)))))

(defn- handle-nrepl-message [socket msg]
  (let [msg-clj (js->clj msg :keywordize-keys true)
        op (keyword (:op msg-clj))]
    (case op
      :clone (let [session-id (str (random-uuid))
                   response (clj->js (cond-> {:new-session session-id
                                              :status ["done"]
                                              :id (:id msg-clj)}
                                       (:session msg-clj) (assoc :session (:session msg-clj))))]
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
                          (:session msg-clj) (assoc :session (:session msg-clj))))]
                  (send-bencode socket response))

      :eval (do-eval socket msg-clj)

      :load-file (do-eval socket (assoc msg-clj :code (:file msg-clj)))

      :classpath (let [response (clj->js
                                  (cond-> {:classpath [(cwd)]
                                           :status ["done"]
                                           :id (:id msg-clj)}
                                    (:session msg-clj) (assoc :session (:session msg-clj))))]
                   (send-bencode socket response))

      :close (let [{:keys [session id]} msg-clj]
               (when session
                 (swap! nrepl-sessions dissoc session))
               (let [response (clj->js (cond-> {:status ["done" "session-closed"]
                                                :id id}
                                         session (assoc :session session)))]
                 (send-bencode socket response)))

      :macroexpand (forward-to-browser socket msg-clj)

      (forward-to-browser socket msg-clj))))

(defn- handle-nrepl-client [socket]
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
                             (when-not (or (str/includes? (.-message e) "Unexpected end of buffer")
                                           (str/includes? (.-message e) "Not a string")
                                           (str/includes? (.-message e) "Invalid data"))
                               (js/console.error "nREPL stream error, closing connection:" e)
                               (.end socket))
                             :decode-error))]
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
               (js/console.log (str "nREPL server started on port " port " on host " host " - nrepl://" host ":" port))))))

(defn start-ws-server! [port]
  (reset! ws-port port)
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
                    (if socket
                      (send-bencode socket (clj->js response))
                      (js/console.error "nREPL: No socket for session" session-id)))))
           (.on ws "close" #(do (js/console.log "nREPL browser disconnected.")
                                (reset! nrepl-ws-channel nil)))))))

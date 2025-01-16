(ns hello)

(defn goodbye [_req res]
  (.send res "Goodbye!"))

(fn [req res]
  (js/console.log (aget req "path"))
  (.send res "Hello world!"))

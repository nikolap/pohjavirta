(ns pohjavirta.server-test
  (:require [clojure.test :refer :all]
            [pohjavirta.server :as server]
            [pohjavirta.response :as response]
            [pohjavirta.async :as a]
            [hikari-cp.core :as hikari])
  (:import (java.nio ByteBuffer)
           (java.util.concurrent CompletableFuture)
           (io.undertow.server HttpHandler HttpServerExchange)
           (io.undertow.util Headers)
           (java.util.concurrent ThreadLocalRandom)
           (java.util.function Function)))

(set! *warn-on-reflection* true)

(def http-handler
  (let [bytes (.getBytes "Hello, World!")
        buffer (-> bytes count ByteBuffer/allocateDirect (.put bytes) .flip)]
    (reify HttpHandler
      (handleRequest [_ exchange]
        (-> exchange
            (.getResponseHeaders)
            (.put Headers/CONTENT_TYPE "text/plain"))
        (-> exchange
            (.getResponseSender)
            (.send (.duplicate ^ByteBuffer buffer)))))))

(defn handler [_]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "hello World!"})

(defn handler [_]
  (response/->Response 200 {"Content-Type" "text/plain"} "hello World?"))

(defn handler [_]
  (let [f (CompletableFuture.)]
    (future (.complete f {:status 200
                          :headers {"Content-Type" "text/plain"}
                          :body "hello Future!"}))
    f))

(require '[promesa.core :as p])
(require '[porsas.async :as pa])
(require '[porsas.core :as ps])
(require '[jsonista.core :as j])

(def async-pool
  (pa/pool
    {:uri "postgresql://localhost:5432/hello_world"
     :user "benchmarkdbuser"
     :password "benchmarkdbpass"
     :pipelining-limit 4
     :size (* 2 (.availableProcessors (Runtime/getRuntime)))}))

(def async-mapper (pa/data-mapper {:row (pa/rs->compiled-record)}))

(def jdbc-pool
  (hikari/make-datasource
      {:jdbc-url "jdbc:postgresql://localhost:5432/hello_world"
       :username "benchmarkdbuser"
       :password "benchmarkdbpass"
       :maximum-pool-size 256}))

(def jdbc-mapper (ps/data-mapper {:row (ps/rs->compiled-record)}))

(defn random []
  (unchecked-inc (.nextInt (ThreadLocalRandom/current) 10000)))

(defn handler2 [_]
  (let [world (with-open [con (ps/get-connection jdbc-pool)]
                (ps/query-one jdbc-mapper con ["SELECT id, randomnumber from WORLD where id=?" (random)]))]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (j/write-value-as-bytes world)}))

(def http-handler
  (reify HttpHandler
    (handleRequest [_ exchange]
      #_(.startBlocking exchange)
      (.dispatch
        ^HttpServerExchange exchange
        ^Runnable (^:once fn* []
                    (let [world (with-open [con (ps/get-connection jdbc-pool)]
                                  (ps/query-one jdbc-mapper con ["SELECT id, randomnumber from WORLD where id=?" (random)]))]
                      (response/send-response
                        {:status 200
                         :headers {"Content-Type" "application/json"}
                         :body (j/write-value-as-bytes world)}
                        exchange)))))))

(def http-handler
  (reify HttpHandler
    (handleRequest [_ exchange]
      #_(.startBlocking exchange)
      (.dispatch
        ^HttpServerExchange exchange
        ^Runnable (^:once fn* []
                    (-> (pa/query-one async-mapper async-pool ["SELECT id, randomnumber from WORLD where id=$1" (random)])
                        (p/then (fn [world]
                                  {:status 200
                                   :headers {"Content-Type" "application/json"}
                                   :body (j/write-value-as-bytes world)}))))))))

(defn handler [_]
  (-> (a/promise "Hello, Async?")
      (a/then (fn [response]
                {:status 200,
                 :headers {"Content-Type" "text/plain"}
                 :body response}))))

(defn handler [_]
  (let [cf (CompletableFuture.)]
    (.complete cf "Hello, Async?")
    (.thenApply cf (reify Function
                     (apply [_ response]
                       {:status 200,
                        :headers {"Content-Type" "text/plain"}
                        :body response})))))

(defn handler [_]
  (-> (p/promise "Hello, Async!")
      (p/then (fn [message]
                {:status 200,
                 :headers {"Content-Type" "text/plain"}
                 :body message}))))

(defn handler [_]
  (-> (pa/query-one async-mapper async-pool ["SELECT id, randomnumber from WORLD where id=$1" (random)])
      (.thenApply (reify Function
                    (apply [_ world]
                      {:status 200
                       :headers {"Content-Type" "application/json"}
                       :body (j/write-value-as-bytes world)})))))

(defn handler [_]
  (-> (pa/query-one async-mapper async-pool ["SELECT id, randomnumber from WORLD where id=$1" (random)])
      (a/then (fn [world]
                {:status 200
                 :headers {"Content-Type" "application/json"}
                 :body (j/write-value-as-bytes world)}))))

(def handler (server/dispatch handler2))

(defn handler2 [_]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "hello World!"})

(def handler (server/constantly handler2))

(comment
  (def server (server/create #'handler))
  (def server (server/create http-handler))
  (server/start server)
  (server/stop server))

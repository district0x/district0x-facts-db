(ns district0x-facts-db.browser-installer
  (:require [district0x-facts-db.core :refer [install-facts-filter! get-block-number]]
            [datascript.core :as d]
            [clojure.core.async :as async]
            [ajax.core :refer [ajax-request] :as ajax]
            [ajax.edn :as ajax-edn]
            [district0x-facts-db.indexeddb :as idb])
  (:require-macros [district0x-facts-db.utils :refer [<?]]))


(def batch-timeout 2000)

(defn wait-for-load []
  (let [out-ch (async/promise-chan)]
    (.addEventListener js/window "load" #(async/put! out-ch true))
    out-ch))

(defn fact->ds-fact [{:keys [entity attribute value add block-num]}]
  (with-meta [(if add :db/add :db/retract) entity attribute value]
    {:block-num block-num}))

(defn calc-percentage [start-block target-block block-num]
  (quot (* 100 (- block-num start-block)) (- target-block start-block)))

(defn transact-facts-batch [finish-ch ds-conn transact-batch-size progress-cb facts-to-transact start-block target-block]
  (if (empty? facts-to-transact)
    (async/put! finish-ch true)

    (let [batch (take transact-batch-size facts-to-transact)]
      (d/transact! ds-conn batch)

      (progress-cb {:state :installing-facts :percentage (calc-percentage start-block target-block (-> batch last meta :block-num))})

      (js/setTimeout #(transact-facts-batch finish-ch
                                            ds-conn
                                            transact-batch-size
                                            progress-cb
                                            (drop transact-batch-size facts-to-transact)
                                            start-block
                                            target-block)
                     0))))

(defn pre-fetch [url pulls-and-qs]
  (let [out-ch (async/promise-chan)]
   (ajax-request {:method          :post
                  :uri             (str url "/datoms")
                  :timeout         30000
                  :params {:datoms-for pulls-and-qs}
                  :format (ajax-edn/edn-request-format)
                  :response-format (ajax/raw-response-format)
                  :handler (fn [[ok? res] result]
                             (if ok?
                               (let [datoms (->> (cljs.reader/read-string res)
                                                 :datoms
                                                 (mapv (fn [[e a v]]
                                                         [:db/add e a v])))]
                                 (async/put! out-ch datoms))
                               (do
                                 (.error js/console "Error pre fetching datoms")
                                 (async/close! out-ch))))})
   out-ch))

(defonce worker (js/Worker. "worker.js"))
(defonce worker-responses-handlers (atom {}))

(set! (.-onmessage worker)
      (fn [e]
        (.log js/console "Received from worker " (.-data e))
        (let [ev-data (.-data e)
              handle (get @worker-responses-handlers (.-id ev-data))
              result (.-result ev-data)]
          (swap! worker-responses-handlers dissoc (.-id ev-data))
          (handle (not (.-error ev-data))
                  result))))

(defn call-worker [fn-name args callback]
  (let [id (str (random-uuid))]
    (swap! worker-responses-handlers assoc id callback)
    (.postMessage worker
                  (clj->js {:id id
                            :fn fn-name
                            :args args}))))


(defn load-db-snapshot [url]
  (println "Downloading snapshot")
  (let [out-ch (async/chan)]
    (call-worker :download-snapshot
                 [url]
                 (fn [success result]
                   (if success
                     (let [val {:db-facts (->> (aget result "db-facts")
                                               (mapv (fn [[e a v tx add?]]
                                                       [e (keyword a) v tx add?])))
                                :last-seen-block (aget result "last-seen-block")}]
                       (async/put! out-ch val))
                     (async/close! out-ch))))
    out-ch))

(defn install [{:keys [progress-cb web3 preindexer-url facts-db-address ds-conn pre-fetch-datoms transact-batch-size]}]
  (async/go
    (try
      (let [stop-watch-start (.getTime (js/Date.))]
        (<? (wait-for-load))
        (println "Page loaded")

        (when pre-fetch-datoms
          (println "Pre fetching datoms")
          (let [datoms (<? (pre-fetch preindexer-url pre-fetch-datoms))]
            (d/transact! ds-conn datoms)))

        (<? (idb/init-indexed-db!))
        (println "IndexedDB initialized")

        ;; First try from IndexedDB
        (let [current-block-number (<? (get-block-number web3))
              _ (println "Current block number is " current-block-number)
              [catched-facts store?] (let [idb-facts (<? (idb/every-store-fact-ch))
                                           idb-facts-count (count idb-facts)]
                                       (if (pos? idb-facts-count)
                                         (do
                                           (println "IndexedDB contains " idb-facts-count "facts")
                                           [idb-facts false])

                                         ;; NO IndexDB facts, try to load a snapshot
                                         (let [_ (println "We DON'T have IndexedDB facts, lets try to load a snapshot")
                                               {:keys [db-facts] :as v} (<? (load-db-snapshot preindexer-url))]
                                           (if (not-empty db-facts)
                                             (let []
                                               (println "We have a snapshot!")
                                               [(->> db-facts
                                                     (map (fn [[e a v block-num x]]
                                                            {:entity e :attribute a :value v :block-num block-num :add x})))
                                                true])

                                             (do (println "We couldn't download a snapshot")
                                                 [[] false])))))
              min-block (or (->> catched-facts (map :block-num) (apply min)) 0)
              max-block (or (->> catched-facts (map :block-num) (apply max)) 0)]

          (println "Transacting " (count catched-facts) " catched facts. Min known block " min-block ". Max known block " max-block)

          (when-not (empty? catched-facts)
            (let [ds-facts (->> catched-facts
                                (mapv fact->ds-fact))]
              (if transact-batch-size
                (do
                  (when (< transact-batch-size 32) (throw (js/Error. "transact-batch-size should be nil or >= 32")))
                  (let [finish-ch (async/chan)]
                    (transact-facts-batch finish-ch ds-conn transact-batch-size progress-cb ds-facts min-block current-block-number)
                    (<? finish-ch)))
                (d/transact! ds-conn ds-facts))))

          (when store?
            (println "Storing facts")
            (idb/store-facts catched-facts))

          ;; we already or got facts from IndexedDB or downloaded a snapshot, or we don't have anything
          ;; in any case sync the remainning from blockchain
          (println "Let's sync the remainning facts directly from the blockchain. Last block seen " max-block)

          ;; keep listening to new facts and transacting them to datascript db
          (let [new-facts-ch (install-facts-filter! web3 facts-db-address max-block)]
            (loop [[nf to-or-fact] (async/alts! [new-facts-ch (async/timeout batch-timeout)])
                   batch []]

              (if (and (= to-or-fact new-facts-ch)
                       (< (count batch) transact-batch-size))
                ;; keep accumulating in the batch while we are getting facts
                ;; and batch count is less than transact-batch-size
                (recur (async/alts! [new-facts-ch (async/timeout batch-timeout)])
                       (conj batch nf))

                ;; if we reach transact-batch-size or a batch-timeout
                ;; transact and store whatever we have
                (let [finish-ch (async/chan)]
                  (when (pos? (count batch))
                    (println "Ready, transacting " (count batch))
                    (transact-facts-batch finish-ch
                                          ds-conn
                                          transact-batch-size
                                          progress-cb
                                          (map fact->ds-fact batch)
                                          min-block
                                          current-block-number)
                    (<? finish-ch)
                    (idb/store-facts batch))
                  (recur (async/alts! [new-facts-ch (async/timeout batch-timeout)])
                         (if nf [nf] []))))
              ;; (progress-cb {:state :ready :startup-time-in-millis (- (.getTime (js/Date.)) stop-watch-start)})
              ;; (println "Started in :" (- (.getTime (js/Date.)) stop-watch-start) " millis")
              ;; (println "Transacting " (:block-num nf) " of " current-block-number)

              ))))
      (catch js/Error e (.error js/console e) (throw e)))))

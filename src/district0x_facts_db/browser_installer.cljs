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
  (let [out-ch (async/chan)]
    (.addEventListener js/window "load" #(async/put! out-ch true))
    out-ch))

(defn fact->ds-fact [{:keys [entity attribute value add block-num]}]
  (with-meta [(if add :db/add :db/retract) entity attribute value]
    {:block-num block-num}))

(defn transact-facts-batch [finish-ch ds-conn transact-batch-size progress-cb facts-to-transact target-block]
  (if (empty? facts-to-transact)
    (async/put! finish-ch true)

    (let [batch (take transact-batch-size facts-to-transact)]
      (d/transact! ds-conn batch)

      (progress-cb {:state :installing-facts :percentage (quot (* 100 (-> batch last meta :block-num)) target-block)})

      (js/setTimeout #(transact-facts-batch finish-ch
                                            ds-conn
                                            transact-batch-size
                                            progress-cb
                                            (drop transact-batch-size facts-to-transact)
                                            target-block)
                     0))))

(defn pre-fetch [ds-conn url pulls-and-qs]
  (let [out-ch (async/chan)]
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
                                 (d/transact! ds-conn datoms)
                                 (async/put! out-ch true))
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
                                               (mapv (fn [[e a v]]
                                                       [e (keyword a) v true])))
                                :last-seen-block (aget result "last-seen-block")}]
                       (async/put! out-ch val))
                     (async/close! out-ch))))
    out-ch))


(defn install [{:keys [progress-cb web3 preindexer-url facts-db-address ds-conn pre-fetch-datoms transact-batch-size]}]
  (async/go
    (try
      (let [stop-watch-start (.getTime (js/Date.))
            first-block 0
            last-block-so-far (atom first-block)
            facts-to-transact (atom #{})
            facts-to-store (atom #{})]
        (<? (wait-for-load))
        (println "Page loaded")

        (when pre-fetch-datoms
          (println "Pre fetching datoms")
          (<? (pre-fetch ds-conn preindexer-url pre-fetch-datoms)))

        (<? (idb/init-indexed-db!))
        (println "IndexedDB initialized")

        ;; First try from IndexedDB
        (let [current-block-number (<? (get-block-number web3))
              idb-facts-count (<? (idb/get-store-facts-count))]
          (println "Current block number is " current-block-number)
          (println "IndexedDB contains " idb-facts-count "facts")

          (if (pos? idb-facts-count)
            (let [last-stored-bn (<? (idb/last-stored-block-number))
                  idb-facts (->> (<? (idb/every-store-fact-ch))
                                 (mapv fact->ds-fact))]
              (println "We have facts on IndexedDB. Last stored block number is " last-stored-bn)
              (reset! last-block-so-far last-stored-bn)
              (swap! facts-to-transact (fn [fs] (into fs idb-facts))))

            ;; NO IndexDB facts, try to load a snapshot
            (let [_ (println "We DON'T have IndexedDB facts, lets try to load a snapshot")
                  {:keys [db-facts last-seen-block] :as v} (<? (load-db-snapshot preindexer-url))]
              (if (not-empty db-facts)
                (let []
                  (reset! last-block-so-far last-seen-block)
                  (println "we have a snapshot, installing it")
                  (swap! facts-to-transact (fn [fs] (->> db-facts
                                                         (mapv (fn [[e a v x]] [(if x :db/add :db/retract) e a v]))
                                                         (into fs))))

                  (swap! facts-to-store (fn [fs]
                                          (->> db-facts
                                               (map (fn [[e a v x]]
                                                      ;; TODO Hack, storing last-seen-block as block number because
                                                      ;; not transfering it in snapshot, works since we are only using
                                                      ;; block number for last seen block reference
                                                      {:entity e :attribute a :value v :block-num last-seen-block :add x}))
                                               (into fs )))))

                (println "We couldn't download a snapshot"))))

          (if transact-batch-size
            (do
              (when (< transact-batch-size 32) (throw (js/Error. "transact-batch-size should be nil or >= 32")))
              (let [finish-ch (async/chan)]
                (transact-facts-batch finish-ch ds-conn transact-batch-size progress-cb @facts-to-transact current-block-number)
                (<? finish-ch)))
            (d/transact! ds-conn (vec @facts-to-transact)))

          (println "Storing facts")
          (idb/store-facts @facts-to-store)

          ;; we already or got facts from IndexedDB or downloaded a snapshot, or we don't have anything
          ;; in any case sync the remainning from blockchain
          (println "Let's sync the remainning facts directly from the blockchain. Last block seen " @last-block-so-far)

          ;; keep listening to new facts and transacting them to datascript db
          (let [new-facts-ch (install-facts-filter! web3 facts-db-address @last-block-so-far)]
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

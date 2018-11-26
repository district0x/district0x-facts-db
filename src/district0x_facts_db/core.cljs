(ns district0x-facts-db.core
  (:require [bignumber.core :as bn]
            [clojure.core.async :as async]
            [web3.core :as web3-core])
  (:require-macros [district0x-facts-db.utils :refer [slurpf]]))

(def facts-db-abi (-> (slurpf "./contracts/build/FactsDb.abi")
                      js/JSON.parse))

(defn build-fact [{:keys [:web3.block/number :web3.event/data] :as a}]
  {:entity (-> data :entity bn/number)
   :attribute (keyword (:attribute data))
   :value (if (bn/bignumber? (:val data))
            (bn/number (:val data))
            (-> data :val))
   :block-num number
   :add (boolean (:add data))})

(defn get-block-number [web3]
  (let [out-ch (async/chan)]
    (web3-core/last-block-number web3
                                 {:on-result (fn [last-block-num]
                                               (async/put! out-ch last-block-num))})
    out-ch))

(defn install-facts-filter!
  [web3 facts-db-address from-block]
  (let [out-ch (async/chan 200000)]
    (web3-core/on-event web3
                        (web3-core/make-contract-instance facts-db-address facts-db-abi)
                        {:from-block from-block}
                        {:on-event-result #(async/put! out-ch (build-fact %))
                         :on-error #(async/put! out-ch (js/Error. "Error in event watcher"))})

    out-ch))

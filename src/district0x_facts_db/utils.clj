(ns district0x-facts-db.utils
  (:require [clojure.core.async :as async]))

(defmacro <? [c]
  `(let [v# (async/<! ~c)]
     (if (isa? js/Error v#)
       (throw v#)
       v#)))

(defmacro slurpf [path]
  (slurp path))

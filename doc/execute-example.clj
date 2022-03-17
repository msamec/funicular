(ns app.funicular-example
  (:require [com.verybigthings.funicular.core :as fun]
            [clojure.edn :as edn]))

(let [raw-edn (edn/read-string (slurp (io/resource "funicular.edn")))
      compiled (fun/compile api)]
  (fun/execute compiled
               {}
               {:command [:api-v1.tracking/log-site-visitor
                          {:page "/content"}]
                :queries {:numbers
                          [:api-v1.content/make-numbers 3]
                          :words
                          [:api-v1.content/make-words 3]}}))
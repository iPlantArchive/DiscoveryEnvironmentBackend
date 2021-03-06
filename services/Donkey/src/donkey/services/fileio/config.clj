(ns donkey.services.fileio.config
  (:require [clojure.core.memoize :as memo]
            [clj-jargon.init :as init]
            [donkey.util.config :as cfg]))


(def jargon-cfg
  (memo/memo #(init/init (cfg/irods-host)
                (cfg/irods-port)
                (cfg/irods-user)
                (cfg/irods-pass)
                (cfg/irods-home)
                (cfg/irods-zone)
                (cfg/irods-resc)
                :max-retries (cfg/irods-max-retries)
                :retry-sleep (cfg/irods-retry-sleep)
                :use-trash   (cfg/irods-use-trash))))

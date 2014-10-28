(ns donkey.services.filesystem.stat
  (:use [clojure-commons.validators]
        [clj-jargon.init :only [with-jargon]]
        [clj-jargon.item-info :only [is-dir? stat]]
        [clj-jargon.item-ops :only [input-stream]]
        [clj-jargon.metadata :only [get-attribute]]
        [clj-jargon.permissions :only [list-user-perms permission-for owns?]])
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [cemerick.url :as url]
            [clojure-commons.file-utils :as ft]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [dire.core :refer [with-pre-hook! with-post-hook!]]
            [donkey.services.filesystem.validators :as validators]
            [donkey.services.filesystem.garnish.irods :as filetypes]
            [clj-icat-direct.icat :as icat]
            [donkey.util.config :as cfg]
            [donkey.services.filesystem.common-paths :as paths]
            [donkey.services.filesystem.icat :as jargon])
  (:import [org.apache.tika Tika]))

(defn- count-shares
  [cm user path]
  (let [filter-users (set (conj (cfg/fs-perms-filter) user (cfg/irods-user)))
        full-listing (list-user-perms cm path)]
    (count
     (filterv
      #(not (contains? filter-users (:user %1)))
      full-listing))))

(defn- merge-counts
  [stat-map cm user path]
  (if (is-dir? cm path)
    (merge stat-map {:file-count (icat/number-of-files-in-folder user (cfg/irods-zone) path)
                     :dir-count  (icat/number-of-folders-in-folder user (cfg/irods-zone) path)})
    stat-map))

(defn- merge-shares
  [stat-map cm user path]
  (if (owns? cm user path)
    (merge stat-map {:share-count (count-shares cm user path)})
    stat-map))

(defn detect-content-type
  [cm path]
  (let [path-type (.detect (Tika.) (ft/basename path))]
    (if (or (= path-type "application/octet-stream")
            (= path-type "text/plain"))
      (.detect (Tika.) (input-stream cm path))
      path-type)))

(defn- merge-type-info
  [stat-map cm user path]
  (if-not (is-dir? cm path)
    (-> stat-map
      (merge {:info-type (filetypes/get-types cm user path)})
      (merge {:content-type (detect-content-type cm path)}))
    stat-map))

(defn path-is-dir?
  [path]
  (with-jargon (jargon/jargon-cfg) [cm]
    (validators/path-exists cm path)
    (is-dir? cm path)))

(defn decorate-stat
  [cm user stat]
  (let [path (:path stat)]
    (-> stat
        (assoc :id         (:value (first (get-attribute cm path "ipc_UUID")))
               :label      (paths/id->label user path)
               :permission (permission-for cm user path))
        (merge-type-info cm user path)
        (merge-shares cm user path)
        (merge-counts cm user path))))

(defn path-stat
  [cm user path]
  (let [path (ft/rm-last-slash path)]
    (log/warn "[path-stat] user:" user "path:" path)
    (validators/path-exists cm path)
    (decorate-stat cm user (stat cm path))))


(defn do-stat
  [params body]
  (let [url     (url/url (cfg/data-info-base-url) "stat-gatherer")
        req-map {:query-params params
                 :content-type :json
                 :body         (json/encode body)}]
    (:body (http/post (str url) req-map))))

(with-pre-hook! #'do-stat
  (fn [params body]
    (paths/log-call "do-stat" params body)
    (validate-map params {:user string?})
    (validate-map body {:paths vector?})
    (validate-map body {:paths #(not (empty? %1))})
    (validate-map body {:paths #(every? (comp not string/blank?) %1)})
    (validators/validate-num-paths (:paths body))))

(with-post-hook! #'do-stat (paths/log-func "do-stat"))

(ns donkey.services.metadata.apps
  (:use [clojure-commons.validators :only [validate-map]]
        [donkey.auth.user-attributes :only [current-user with-directory-user]]
        [donkey.util :only [is-uuid?]]
        [kameleon.uuids :only [uuidify]]
        [korma.db :only [transaction with-db]]
        [slingshot.slingshot :only [throw+ try+]])
  (:require [cemerick.url :as curl]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure-commons.error-codes :as ce]
            [donkey.clients.metadactyl :as metadactyl]
            [donkey.persistence.jobs :as jp]
            [donkey.persistence.oauth :as op]
            [donkey.services.metadata.agave-apps :as aa]
            [donkey.services.metadata.combined-apps :as ca]
            [donkey.services.metadata.de-apps :as da]
            [donkey.services.metadata.internal-jobs :as internal-jobs]
            [donkey.services.metadata.util :as mu]
            [donkey.util :as util]
            [donkey.util.config :as config]
            [donkey.util.db :as db]
            [donkey.util.service :as service]
            [kameleon.db :as kdb]
            [mescal.de :as agave])
  (:import [java.util UUID]))

(defn- retrieve-app
  [app-id]
  ((comp service/decode-json :body)
   (metadactyl/get-app app-id)))

(defn- get-first-job-step
  [{:keys [id]}]
  (service/assert-found (jp/get-job-step-number id 1) "first step in job" id))

(defn- process-job
  ([agave-client job-id processing-fns]
     (process-job agave-client job-id (jp/get-job-by-id (UUID/fromString job-id)) processing-fns))
  ([agave-client job-id job {:keys [process-agave-job process-de-job]}]
     (when-not job
       (service/not-found "job" job-id))
     (if (= jp/de-job-type (:job-type job))
       (process-de-job job)
       (process-agave-job agave-client (get-first-job-step job)))))

(defn- determine-batch-status
  [{:keys [id]}]
  (let [children (jp/list-child-jobs id)]
    (cond (every? (comp mu/is-completed? :status) children) jp/completed-status
          (some (comp mu/is-running? :status) children)     jp/running-status
          :else                                             jp/submitted-status)))

(defn- update-batch-status
  [batch completion-date]
  (when batch
    (let [new-status (determine-batch-status batch)]
      (when-not (= (:status batch) new-status)
        (jp/update-job (:id batch) {:status new-status :end-date completion-date})
        (jp/update-job-steps (:id batch) new-status completion-date)
        (mu/send-job-status-notification batch new-status completion-date)))))

(defn- agave-authorization-uri
  [state-info]
  (let [username (:username current-user)
        state    (op/store-authorization-request username state-info)]
    (-> (curl/url (config/agave-oauth-base) "authorize")
        (assoc :query {:response_type "code"
                       :client_id     (config/agave-key)
                       :redirect-uri  (config/agave-redirect-uri)
                       :state         state})
        (str))))

(defn- agave-authorization-redirect
  [state-info]
  (throw+ {:error_code ce/ERR_TEMPORARILY_MOVED
           :location   (agave-authorization-uri state-info)}))

(defn- add-predicate
  [predicate f]
  (fn [& args]
    (when (predicate)
      (apply f args))))

(defprotocol AppLister
  "Used to list apps available to the Discovery Environment."
  (listAppGroups [_ params])
  (listApps [_ category-id params])
  (searchApps [_ search-term])
  (addFavoriteApp [_ app-id])
  (removeFavoriteApp [_ app-id])
  (rateApp [_ app-id rating comment-id])
  (deleteRating [_ app-id])
  (getApp [_ app-id])
  (getAppDeployedComponents [_ app-id])
  (getAppDetails [_ app-id])
  (getAppDocs [_ app-id])
  (addAppDocs [_ app-id docs])
  (editAppDocs [_ app-id docs])
  (adminAddAppDocs [_ app-id docs])
  (adminEditAppDocs [_ app-id docs])
  (listAppTasks [_ app-id])
  (editWorkflow [_ app-id])
  (copyWorkflow [_ app-id])
  (createPipeline [_ pipeline])
  (updatePipeline [_ app-id pipeline])
  (submitJob [_ submission])
  (countJobs [_ filter include-hidden])
  (listJobs [_ limit offset sort-field sort-order filter include-hidden])
  (syncJobStatus [_ job])
  (updateJobStatus [_ username job job-step status end-time])
  (updateBatchStatus [_ batch completion-date])
  (stopJob [_ job])
  (getJobParams [_ job-id])
  (getAppRerunInfo [_ job-id])
  (urlImport [_ address filename dest-path]))
;; AppLister

(deftype DeOnlyAppLister []
  AppLister

  (getApp [_ app-id]
    (retrieve-app app-id))

  (getAppDocs [_ app-id]
    (metadactyl/get-app-docs app-id))

  (addAppDocs [_ app-id docs]
    (metadactyl/add-app-docs app-id docs))

  (editAppDocs [_ app-id docs]
    (metadactyl/edit-app-docs app-id docs))

  (adminAddAppDocs [_ app-id docs]
    (metadactyl/admin-add-app-docs app-id docs))

  (adminEditAppDocs [_ app-id docs]
    (metadactyl/admin-edit-app-docs app-id docs))

  (updatePipeline [_ app-id pipeline]
    (metadactyl/update-pipeline app-id pipeline))

  (submitJob [_ submission]
    (da/submit-job submission))

  (syncJobStatus [_ job]
    (da/sync-job-status job))

  (updateJobStatus [_ username job job-step status end-time]
    (da/update-job-status username job job-step status end-time))

  (updateBatchStatus [_ batch completion-date]
    (update-batch-status batch completion-date))

  (stopJob [_ job]
    (ca/stop-job job))

  (getJobParams [_ job-id]
    (ca/get-job-params nil (jp/get-job-by-id (UUID/fromString job-id))))

  (getAppRerunInfo [_ job-id]
    (ca/get-app-rerun-info nil (jp/get-job-by-id (UUID/fromString job-id))))

  (urlImport [this address filename dest-path]
    (internal-jobs/submit :url-import this [address filename dest-path])))
;; DeOnlyAppLister

(deftype DeHpcAppLister [agave-client user-has-access-token?]
  AppLister

  (getApp [_ app-id]
    (retrieve-app app-id))

  (getAppDocs [_ app-id]
    (if (is-uuid? app-id)
      (metadactyl/get-app-docs app-id)
      {:app_id        app-id
       :documentation ""
       :references    []}))

  (addAppDocs [_ app-id docs]
    (if (is-uuid? app-id)
      (metadactyl/add-app-docs app-id docs)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     "Cannot edit documentation for HPC apps with this service"})))

  (editAppDocs [_ app-id docs]
    (if (is-uuid? app-id)
      (metadactyl/edit-app-docs app-id docs)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     "Cannot edit documentation for HPC apps with this service"})))

  (adminAddAppDocs [_ app-id docs]
    (if (is-uuid? app-id)
      (metadactyl/admin-add-app-docs app-id docs)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     "Cannot edit documentation for HPC apps with this service"})))

  (adminEditAppDocs [_ app-id docs]
    (if (is-uuid? app-id)
      (metadactyl/admin-edit-app-docs app-id docs)
      (throw+ {:error_code ce/ERR_BAD_REQUEST
               :reason     "Cannot edit documentation for HPC apps with this service"})))

  (updatePipeline [_ app-id pipeline]
    (ca/update-pipeline agave-client app-id pipeline))

  (submitJob [_ submission]
    (ca/submit-job submission))

  (syncJobStatus [_ job]
    (if (user-has-access-token?)
      (ca/sync-job-status agave-client job)
      (da/sync-job-status job)))

  (updateJobStatus [_ username job job-step status end-time]
    (ca/update-job-status agave-client username job job-step status end-time))

  (updateBatchStatus [_ batch completion-date]
    (update-batch-status batch completion-date))

  (stopJob [_ job]
    (ca/stop-job agave-client job))

  (getJobParams [_ job-id]
    (process-job agave-client job-id
                 {:process-de-job    (partial ca/get-job-params agave-client)
                  :process-agave-job aa/get-agave-job-params}))

  (getAppRerunInfo [_ job-id]
    (process-job agave-client job-id
                 {:process-de-job    (partial ca/get-app-rerun-info agave-client)
                  :process-agave-job aa/get-agave-app-rerun-info}))

  (urlImport [this address filename dest-path]
    (internal-jobs/submit :url-import this [address filename dest-path])))
;; DeHpcAppLister

(defn- has-access-token
  [{:keys [api-name] :as server-info} username]
  (seq (op/get-access-token api-name username)))

(defn- get-access-token
  [{:keys [api-name] :as server-info} state-info username]
  (if-let [token-info (op/get-access-token api-name username)]
    (assoc (merge server-info token-info)
      :token-callback  (partial op/store-access-token api-name username)
      :reauth-callback (partial agave-authorization-redirect state-info))
    (agave-authorization-redirect state-info)))

(defn- get-agave-client
  [state-info username]
  (agave/de-agave-client-v2
   (config/agave-base-url)
   (config/agave-storage-system)
   (partial get-access-token (config/agave-oauth-settings) state-info username)
   (config/agave-jobs-enabled)))

(defn- get-de-hpc-app-lister
  [state-info username]
  (DeHpcAppLister. (get-agave-client state-info username)
                   (partial has-access-token (config/agave-oauth-settings) username)))

(defn- get-app-lister
  ([]
     (get-app-lister ""))
  ([state-info]
     (get-app-lister state-info (:username current-user)))
  ([state-info username]
     (if (config/agave-enabled)
       (get-de-hpc-app-lister state-info username)
       (DeOnlyAppLister.))))

(defn get-app-docs
  [app-id]
  (service/success-response
    (.getAppDocs (get-app-lister) app-id)))

(defn add-app-docs
  [app-id body]
  (service/success-response
    (.addAppDocs (get-app-lister) app-id (service/decode-json body))))

(defn edit-app-docs
  [app-id body]
  (service/success-response
    (.editAppDocs (get-app-lister) app-id (service/decode-json body))))

(defn admin-add-app-docs
  [app-id body]
  (service/success-response
    (.adminAddAppDocs (get-app-lister) app-id (service/decode-json body))))

(defn admin-edit-app-docs
  [app-id body]
  (service/success-response
    (.adminEditAppDocs (get-app-lister) app-id (service/decode-json body))))

(defn submit-job
  [body]
  (with-db db/de
    (transaction
     (service/success-response
      (.submitJob (get-app-lister) (service/decode-json body))))))

(defn- get-unique-job-step
  "Gest a unique job step for an external ID. An exception is thrown if no job step
  is found or if multiple job steps are found."
  [external-id]
  (let [job-steps (jp/get-job-steps-by-external-id external-id)]
    (when (empty? job-steps)
      (service/not-found "job step" external-id))
    (when (> (count job-steps) 1)
      (service/not-unique "job step" external-id))
    (first job-steps)))

(defn update-de-job-status
  "Updates the job status. Important note: this function currently assumes that the
  external identifier is unique."
  [external-id status end-date]
  (with-db db/de
    (transaction
     (if (= status jp/submitted-status)
       (service/success-response)
       (let [job-step                   (get-unique-job-step external-id)
             job-step                   (jp/lock-job-step (:job-id job-step) external-id)
             {:keys [username] :as job} (jp/lock-job (:job-id job-step))
             batch                      (when (:parent-id job) (jp/lock-job (:parent-id job)))
             end-date                   (kdb/timestamp-from-str end-date)
             app-lister                 (get-app-lister "" username)]
         (service/assert-found job "job" (:job-id job-step))
         (with-directory-user [username]
           (try+
            (.updateJobStatus app-lister username job job-step status end-date)
            (when batch (.updateBatchStatus app-lister batch end-date))
            (catch Object o
              (let [msg (str "DE job status update failed for " external-id)]
                (log/warn o msg)
                (throw+))))))))))

(defn update-agave-job-status
  [uuid status end-time external-id]
  (with-db db/de
    (transaction
     (let [uuid                       (UUID/fromString uuid)
           job-step                   (jp/lock-job-step uuid external-id)
           {:keys [username] :as job} (jp/lock-job uuid)
           batch                      (when (:parent-id job) (jp/lock-job (:parent-id job)))
           end-time                   (kdb/timestamp-from-str end-time)
           app-lister                 (get-app-lister "" username)]
       (service/assert-found job "job" uuid)
       (service/assert-found job-step "job step" (str uuid "/" external-id))
       (with-directory-user [username]
         (try+
          (.updateJobStatus app-lister username job job-step status end-time)
          (when batch (.updateBatchStatus app-lister batch end-time))
          (catch Object o
            (let [msg (str "Agave job status update failed for " uuid "/" external-id)]
              (log/warn o msg)
              (throw+)))))))))

(defn- sync-job-status
  [job]
  (with-directory-user [(:username job)]
    (try+
     (log/warn "synchronizing the job status for" (:id job))
     (transaction (.syncJobStatus (get-app-lister "" (:username job)) job))
     (catch Object e
       (log/error e "unable to sync the job status for job" (:id job))))))

(defn sync-job-statuses
  []
  (log/warn "synchronizing job statuses")
  (with-db db/de
    (try+
     (dorun (map sync-job-status (jp/list-incomplete-jobs)))
     (catch Object e
       (log/error e "error while obtaining the list of jobs to synchronize."))))
  (log/warn "done syncrhonizing job statuses"))

(defn- validate-job-ownership
  [{:keys [id user]}]
  (let [authenticated-user (:username current-user)]
    (when-not (= user authenticated-user)
      (throw+ {:error_code ce/ERR_NOT_OWNER
               :reason     (str authenticated-user " does not own job " id)}))))

(defn- log-missing-job
  [extant-ids id]
  (when-not (extant-ids id)
    (log/warn "attempt to delete missing job" id "ignored")))

(defn- log-already-deleted-job
  [{:keys [id deleted]}]
  (when deleted (log/warn "attempt to delete deleted job" id "ignored")))

(defn- validate-job-deletion-request
  [ids]
  (let [jobs (jp/list-jobs-to-delete ids)]
    (dorun (map validate-job-ownership jobs))
    (dorun (map (partial log-missing-job (set (map :id jobs))) ids))
    (dorun (log-already-deleted-job jobs))))

(defn- delete-selected-jobs
  [ids]
  (with-db db/de
    (transaction
     (validate-job-deletion-request ids)
     (jp/delete-jobs ids)
     (service/success-response))))

(defn delete-jobs
  [body]
  (let  [request (service/decode-json body)]
    (validate-map request {:analyses vector?})
    (delete-selected-jobs (map uuidify (:analyses request)))))

(defn delete-job
  [job-id]
  (delete-selected-jobs [(uuidify job-id)]))

(defn- validate-job-existence
  [id]
  (when-not (jp/get-job-by-id id)
    (service/not-found "job" id)))

(defn- validate-job-update
  [body]
  (let [supported-fields #{:name :description}
        invalid-fields   (remove supported-fields (keys body))]
    (when (seq invalid-fields)
      (throw+ {:error_code ce/ERR_BAD_OR_MISSING_FIELD
               :reason     (str "unrecognized fields: " invalid-fields)}))))

(defn update-job
  [id body]
  (with-db db/de
    (transaction
     (let [id   (UUID/fromString id)
           body (service/decode-json body)]
       (validate-job-existence id)
       (validate-job-update body)
       (-> (jp/update-job id body)
           (dissoc :submission)
           (service/success-response))))))

(defn stop-job
  [id]
  (with-db db/de
    (transaction
     (let [id  (UUID/fromString id)
           job (jp/get-job-by-id id)]
       (when-not job
         (service/not-found "job" id))
       (when-not (= (:username job) (:username current-user))
         (service/not-owner "job" id))
       (when (mu/is-completed? (:status job))
         (service/bad-request (str "job, " id ", is already completed or canceled")))
       (.stopJob (get-app-lister) job)
       (service/success-response {:id (str id)})))))

(defn get-parameter-values
  [job-id]
  (with-db db/de
    (service/success-response (.getJobParams (get-app-lister) job-id))))

(defn get-app-rerun-info
  [job-id]
  (with-db db/de
    (service/success-response (.getAppRerunInfo (get-app-lister) job-id))))

(defn url-import
  [address filename dest-path]
  (with-db db/de
    (.urlImport (get-app-lister) address filename dest-path)))

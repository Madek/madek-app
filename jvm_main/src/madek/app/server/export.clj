(ns madek.app.server.export
  (:require
    [madek.app.server.export.files :as files :refer [download-media-files]]
    [madek.app.server.export.index-html :as index-html]
    [madek.app.server.export.meta-data :as meta-data :refer [meta-data write-meta-data]]
    [madek.app.server.export.meta-data-schema :as meta-data-schema]
    [madek.app.server.state :as state]
    [madek.app.server.utils :refer [deep-merge presence]]

    [json-roa.client.core :as roa]
    [clj-time.core :as time]

    [cheshire.core :as cheshire]
    [clojure.java.io :as io]


    [clj-logging-config.log4j :as logging-config]
    [clojure.tools.logging :as logging]
    [logbug.catcher :as catcher :refer [snatch]]
    [logbug.thrown :as thrown]
    [logbug.debug :as debug :refer [identity-with-logging I> I>>]]
    )

    (:import
    [java.io File]
    [java.nio.file Files Paths]))


;### Path Helper ##############################################################

(defn nio-path [s] (Paths/get s (into-array [""])))

(defn symlink [id source target]
  (snatch
    {:level :debug
     :throwable java.nio.file.FileAlreadyExistsException}
    (swap! state/db
           (fn [db id source target]
             (deep-merge db {:download
                             {:items
                              {id
                               {:links
                                {source target}}}}}))
           id source target)
    (Files/createSymbolicLink
      (nio-path source)
      (nio-path target)
      (make-array java.nio.file.attribute.FileAttribute 0))))

;### title and prefix #########################################################

(defn get-prefix [prefix-meta-key media-resource]
  (-> media-resource
      (roa/relation :meta-data)
      (roa/get {:meta_keys (cheshire/generate-string [(str prefix-meta-key)])})
      roa/coll-seq
      first
      (roa/get {})
      roa/data
      :value str))

(defn useableFileName [s]
  (.replaceAll s "[^a-zA-Z0-9 ]" ""))

(defn path-prefix [prefix-meta-key media-resource]
  (let [prefix-part-one (if-not (presence prefix-meta-key) ""
                          (if-let [mk-value (get-prefix
                                              prefix-meta-key media-resource)]
                            (str (useableFileName mk-value) "_") ""))]
    (str prefix-part-one (-> media-resource roa/data :id))))


;### DL Media-Entry ###########################################################

(defn set-item-to-finished [id]
  (swap! state/db
         (fn [db id]
           (deep-merge db
                       {:download
                        {:items
                         {id
                          {:state "passed"
                           :download_finished-at (str (time/now))}}}}))
         id))

(defn download-media-entry
  ([id target-dir skip-media-files? prefix-meta-key api-entry-point api-http-opts]
   (catcher/with-logging {}
     (let [media-entry (I> identity-with-logging
                           (roa/get-root api-entry-point
                                         :default-conn-opts api-http-opts)
                           (roa/relation :media-entry)
                           (roa/get {:id id}))]
       (download-media-entry skip-media-files? prefix-meta-key target-dir media-entry))))
  ([skip-media-files? prefix-meta-key dir-path media-entry]
   (catcher/with-logging {}
     (let [id (-> media-entry roa/data :id)
           entry-prefix-path (path-prefix prefix-meta-key media-entry)
           entry-dir-path (str dir-path File/separator entry-prefix-path)
           meta-data (meta-data media-entry)]
       (if (-> @state/db :download :items (get id))
         (let [target  (-> @state/db :download :items (get id) :path)]
           (symlink id entry-dir-path target))
         (do (swap! state/db (fn [db uuid media-entry]
                               (assoc-in db [:download :items (str id)] media-entry))
                    id (assoc (roa/data media-entry)
                              :state "downloading"
                              :errors {}
                              :type "MediaEntry"
                              :path entry-dir-path
                              :download_started-at (str (time/now))))
             (io/make-parents entry-dir-path)
             (write-meta-data entry-dir-path meta-data id)
             (index-html/write entry-dir-path
                               meta-data
                               (-> media-entry roa/data (assoc :type :media-entry)))
             (when-not skip-media-files?
               (download-media-files entry-dir-path media-entry))
             (set-item-to-finished id)))))))


;### check credentials ########################################################

(defn check-credentials [api-entry-point api-http-opts]
  (let [response (-> (roa/get-root api-entry-point :default-conn-opts api-http-opts)
                     (roa/relation :auth-info)
                     (roa/get {}))]
    (logging/debug (-> response roa/data))))


;### DL Set ###################################################################

(declare download-set)

(defn download-media-entries-for-set [id target-dir-path skip-media-files? prefix-meta-key
                                      api-entry-point api-http-opts]
  (let [me-get-opts (merge {:collection_id id}
                           (if (or (:basic-auth api-http-opts)
                                   (-> api-http-opts :cookies (get "madek-session")))
                             {:me_get_full_size "true"}
                             {:public_get_full_size "true"}))]
    (doseq [me-rel (I> identity-with-logging
                       (roa/get-root api-entry-point
                                     :default-conn-opts api-http-opts)
                       (roa/relation :media-entries)
                       (roa/get me-get-opts)
                       roa/coll-seq)]
      (download-media-entry skip-media-files? prefix-meta-key target-dir-path (roa/get me-rel {})))))

(defn download-collections-for-collection [collection target-dir-path recursive? skip-media-files?
                                           prefix-meta-key api-entry-point api-http-opts]
  (let [coll-get-opts (if (or (:basic-auth api-http-opts)
                              (-> api-http-opts :cookies (get "madek-session")))
                        {:me_get_full_size "true"}
                        {:public_get_full_size "true"})]
    (doseq [collection  (I>> identity-with-logging
                             (I> identity-with-logging
                                 collection
                                 (roa/relation :collections)
                                 (roa/get {}) ;TODO set coll-get-opts
                                 roa/coll-seq)
                             (map #(roa/get % {})))]
      (download-set
        (-> collection roa/data :id)
        target-dir-path recursive? skip-media-files? prefix-meta-key api-entry-point api-http-opts))))

(defn download-set [id dl-path recursive? skip-media-files? prefix-meta-key
                    api-entry-point api-http-opts]
  (let [collection (-> (roa/get-root api-entry-point
                                     :default-conn-opts api-http-opts)
                       (roa/relation :collection )
                       (roa/get {:id id}))
        path-prefix (path-prefix prefix-meta-key collection)
        target-dir-path (str dl-path File/separator path-prefix)
        meta-data (meta-data collection)]
    (if (-> @state/db :download :items (get id))
      (let [target  (-> @state/db :download :items (get id) :path)]
        (symlink id target-dir-path target))
      (catcher/with-logging {}
        (swap! state/db (fn [db id] (deep-merge db {:download {:items {id {}}}})) id)
        (swap! state/db (fn [db uuid collection]
                          (assoc-in db [:download :items (str id)] collection))
               id (assoc (roa/data collection)
                         :state "downloading"
                         :errors {}
                         :type "Collection"
                         :path target-dir-path
                         :download_started-at (str (time/now))))
        (io/make-parents target-dir-path)
        (write-meta-data target-dir-path meta-data id)
        (index-html/write target-dir-path
                          meta-data
                          (-> collection roa/data (assoc :type :collection)))
        (download-media-entries-for-set
          id target-dir-path skip-media-files? prefix-meta-key api-entry-point api-http-opts)
        (when recursive?
          (download-collections-for-collection
            collection target-dir-path recursive? skip-media-files? prefix-meta-key
            api-entry-point api-http-opts))
        (set-item-to-finished id)))))


;### download meta-data schema ################################################

(def download-meta-data-schema meta-data-schema/download)

;### Debug ####################################################################
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns 'ring.middleware.resource)
;(debug/debug-ns *ns*)
;(debug/debug-ns 'json-roa.client.core)
;(debug/debug-ns 'uritemplate-clj.core)

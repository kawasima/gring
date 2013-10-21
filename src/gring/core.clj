(ns gring.core
  (:use
    [compojure.core :as compojure :only (GET POST PUT ANY defroutes)]
    [clj-jgit.porcelain])

  (:require
    [clojure.java.io :as io]
    [compojure.handler :as handler]
    [ring.util.response :as response]
    [ring.util.io :as ring-io])
  (:import
    [java.io PrintWriter]
    [org.eclipse.jgit.transport UploadPack ReceivePack PacketLineOut
      RefAdvertiser$PacketLineOutRefAdvertiser]
    [org.eclipse.jgit.transport.resolver FileResolver]))

(def config (atom { :upload-pack true 
                    :receive-pack true }))

(def ^:dynamic repository-resolver
  (FileResolver. (io/file "git") true))

(defn open-repo [name]
  (.open repository-resolver nil name))

(defn set-base-path [path]
  (FileResolver. (io/file path) true))

;; packet-line handling
(def pkt-flush "0000")

(defn hdr-nocache [resp]
  (-> resp
    (response/header "Expires" "Fri, 01 Jan 1980 00:00:00 GMT")
    (response/header "Pragma"  "no-cache")
    (response/header "Cache-Control" "no-cache, max-age=0, must-revalidate")))

(defn hdr-cache-forever [resp]
  (let [ now (.getTime (new java.util.Date)) ]
    (-> resp
      (response/header "Expires" (str now))
      (response/header "Date"    (str (+ now 31536000)))
      (response/header "Cache-Control" "public, max-age=31536000"))))

(defn has-access
  ([request rpc] (has-access request rpc false))
  ([request rpc check-content-type]
    (cond
      (and check-content-type
        (not= (request :content-type) (str "application/x-git-" rpc "-request"))) false
      (not (some #{"upload-pack" "receive-pack"} [rpc])) false
      (= rpc "receive-pack") (@config :receive-pack)
      (= rpc "upload-pack")  (@config :upload-pack)
      :else false)))

(defn create-pack [repository rpc]
  (if (= rpc "upload-pack")
    (UploadPack. repository)
    (ReceivePack. repository)))

(defn service-info-refs [repository service-name]
  (let [ pack (create-pack repository service-name)]
    (. pack setBiDirectionalPipe false)
    (-> (response/response
          (ring-io/piped-input-stream
            (fn [ostream]
              (let [ msg (str "# service=git-" service-name "\n")
                     writer (PrintWriter. ostream)]
                (. writer print (format "%04x" (+ (count msg) 4)))
                (. writer print msg)
                (. writer print pkt-flush)
                (. writer flush)
              (. pack sendAdvertisedRefs
                (RefAdvertiser$PacketLineOutRefAdvertiser. (PacketLineOut. ostream)))))))
      (response/content-type (str "application/x-git-" service-name "-advertisement"))
      (hdr-nocache))))

(defn dumb-info-refs [repository]
  (-> (response/file-response
        (str (.. repository getDirectory getPath) "/info/refs"))
    (response/content-type "text/plain")
    (hdr-nocache)))

(defn get-service-type [request]
  (if-let [service-type (get-in request [:params :service])]
    (when (.startsWith service-type "git-")
      (.substring service-type 4))))

(defn get-info-refs [repository request]
  (let [service-name (get-service-type request)]
    (if (has-access request service-name)
      (service-info-refs repository service-name)
      (dumb-info-refs repository))))

(defn get-text-file [repository path]
  (let [ file (io/file (.. repository getDirectory getPath) path)]
    (if (.exists file)
      (-> (response/file-response (.getPath file))
        (response/content-type "text/plain")
        (hdr-nocache))
      (response/not-found "Not Found"))))

(defn get-info-packs [repository]
  (-> (response/file-response
        (str (.. repository getDirectory getPath) "/objects/info/packs"))
    (response/content-type "text/plain; charset=utf-8")
    (hdr-nocache)))

(defn get-loose-object [repository path]
  (-> (response/file-response
        (str (.. repository getDirectory getPath) "/" path))
    (response/content-type "application/x-git-loose-object")
    (hdr-cache-forever)))

(defn get-pack-file [repository path]
  (-> (response/file-response
        (str (.. repository getDirectory getPath) "/" path))
    (response/content-type "application/x-git-packed-objects")
    (hdr-cache-forever)))

(defn get-idx-file [repository path]
  (-> (response/file-response
        (str (.. repository getDirectory getPath) "/" path))
    (response/content-type "application/x-git-packed-objects-toc")
    (hdr-cache-forever)))

(defn service-upload [repository request]
  (let [ pack (create-pack repository "upload-pack")]
    (. pack setBiDirectionalPipe false)
    (-> (response/response (ring-io/piped-input-stream
                             (fn [ostream]
                               (. pack upload (request :body) ostream nil))))
      (response/content-type (str "application/x-git-upload-pack-result")))))

(defn service-receive [repository request]
  (let [ pack (create-pack repository "receive-pack")]
    (. pack setBiDirectionalPipe false)
    (-> (response/response (ring-io/piped-input-stream
                             (fn [ostream]
                               (. pack receive (request :body) ostream nil))))
      (response/content-type (str "application/x-git-receive-pack-result")))))

(defroutes git-routes
  (compojure/context ["/:name.git", :name #"[0-9A-Za-z\-\.]+"] {{repo-name :name} :params}
    (GET "/info/refs" {:as request}
      (get-info-refs (open-repo repo-name) request))
    (POST "/git-upload-pack" {:as request}
      (service-upload (open-repo repo-name) request))
    (POST "/git-receive-pack" {:as request}
      (service-receive (open-repo repo-name) request))
    (GET "/HEAD" {params :params}
      (get-text-file (open-repo repo-name) "HEAD"))
    (GET "/objects/info/alternates" []
      (get-text-file (open-repo repo-name) "objects/info/alternates"))
    (GET "/objects/info/http-alternates" []
      (get-text-file (open-repo repo-name) "objects/info/http-alternates"))
    (GET "/objects/info/packs" []
      (get-info-packs (open-repo repo-name)))
    (GET "/objects/info/*" {params :params}
      (get-text-file (open-repo repo-name) (str "objects/info/" (params :*))))
    (GET ["/objects/:hash2/:hash38", :hash2 #"[0-9a-f]{2}" :hash38 #"[0-9a-f]{38}"] [hash2 hash38]
      (get-loose-object (open-repo repo-name) (str "objects/" hash2 hash38)))
    (GET ["/objects/pack/:pack-file", :pack-file #"[0-9a-f]\.(pack|idx)"] [pack-file]
      (cond
        (. pack-file endsWith ".pack")
        (get-pack-file (open-repo repo-name) (str "objects/pack/" pack-file))
        (. pack-file endsWith ".idx")
        (get-idx-file  (open-repo repo-name) (str "objects/pack/" pack-file))))))

(def app
  (handler/site git-routes))

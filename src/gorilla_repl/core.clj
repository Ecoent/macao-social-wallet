;;;; This file is part of gorilla-repl. Copyright (C) 2014-, Jony Hudson.
;;;;
;;;; gorilla-repl is licenced to you under the MIT licence. See the file LICENCE.txt for full details.

(ns gorilla-repl.core
  (:use compojure.core)
  (:require [compojure.route :as route]
            [org.httpkit.server :as server]
            [gorilla-repl.nrepl :as nrepl]
            [gorilla-repl.websocket-relay :as ws-relay]
            [gorilla-repl.renderer :as renderer] ;; this is needed to bring the render implementations into scope
            [gorilla-repl.version :as version]
            [gorilla-repl.handle :as handle]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [freecoin.config :as config])
  (:gen-class))

;; the combined routes - we serve up everything in the "public" directory of resources under "/".
;; The REPL traffic is handled in the websocket-transport ns.
(defroutes app-routes
  (GET "/load" [] (handle/wrap-api-handler handle/load-worksheet))
  (POST "/save" [] (handle/wrap-api-handler handle/save))
  (GET "/gorilla-files" [] (handle/wrap-api-handler handle/gorilla-files))
  (GET "/config" [] (handle/wrap-api-handler handle/config))
  (GET "/repl" [] ws-relay/ring-handler)
  (route/resources "/" {:root "gorilla-repl-client"})
  (route/resources "/freecoinadmin"  {:root "freecoinadmin"})
  (route/files "/project-files" {:root "."}))


(defn run-gorilla-server
  [conf]
  ;; get configuration information from parameters
  (assert (:gorilla-ip conf) "Need to set the gorilla-ip")
  (let [gorilla-ip (:gorilla-ip conf)
        version (or (:version conf) "develop")
        webapp-requested-port (or (:port conf) 0)
        nrepl-requested-port (or (:nrepl-port conf) 0)  ;; auto-select port if none requested
        nrepl-port-file (io/file (or (:nrepl-port-file conf) ".nrepl-port"))
        gorilla-port-file (io/file (or (:gorilla-port-file conf) ".gorilla-port"))
        project (or (:project conf) "no project")
        keymap (or (:keymap (:gorilla-options conf)) {})
        _ (handle/update-excludes (fn [x] (set/union x (:load-scan-exclude (:gorilla-options conf)))))]
    ;; app startup
    (println "Gorilla-REPL:" version)
    ;; build config information for client
    (handle/set-config :project project)
    (handle/set-config :keymap keymap)
    ;; check for updates
    ;; (version/check-for-update version)  ;; runs asynchronously
    ;; first startup nREPL
    (nrepl/start-and-connect nrepl-requested-port nrepl-port-file)
    ;; and then the webserver
    (let [s (server/run-server #'app-routes {:port webapp-requested-port :join? false :ip gorilla-ip :max-body 500000000})
          webapp-port (:local-port (meta s))]
      (spit (doto gorilla-port-file .deleteOnExit) webapp-port)
      (println (str "Running at "  gorilla-ip ":" webapp-port "/index.html"))
      (println "Ctrl+C to exit."))))

(defn -main
  [& args]
  (let [config-m (config/create-config)]
    (run-gorilla-server {:port (config/gorilla-port config-m)
                         :gorilla-ip (config/gorilla-ip config-m)})))

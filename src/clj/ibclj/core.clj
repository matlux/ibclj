(ns ibclj.core
  (:require [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout go-loop]]
            [capacitor.core :as influx])
  [:import com.ib.controller.ApiController
           (com.ib.controller ApiController$TopMktDataAdapter NewContract)
           ])

(def db-client (influx/make-client {:db "ib_ticks"}))

(defn create-controller []
  (reify
    com.ib.controller.ApiController$IConnectionHandler
    (accountList [this list] (print "AccountList" list))
    (message [this id error-code error-msg] (print id error-code error-msg))
    (error [this e] (print e))
    (show [this msg] (print msg))
    (connected [this] (print "We are connected!"))
    (disconnected [this] (print "We are diconnected!"))

    ))

(defn create-contract [{:keys [type symbol expiry strike right multiplier exchange currency localSymbol tradinClass] :or {expiry "" strike 0.0 right com.ib.controller.Types$Right/None multiplier "" exchange "SMART" currency "USD" localSymbol "" tradinClass ""}}]
  (doto (com.ib.controller.NewContract.)
    (.symbol symbol)
    (.secType type)
    (.expiry expiry)
    (.strike strike)
    (.right right)
    (.multiplier multiplier)
    (.exchange exchange)
    (.currency currency)
    (.localSymbol localSymbol)
    (.tradingClass tradinClass)))

(defn api-ctrl []
  (com.ib.controller.ApiController. (create-controller)
                                    (reify com.ib.controller.ApiConnection$ILogger
                                      (log [this s]         ;(print "loggerin:" s)
                                        ))
                                    (reify com.ib.controller.ApiConnection$ILogger
                                      (log [this s]         ;(print "loggerout:" s)
                                        ))))


(defn create-row [c ^NewContract contract]
  (reify
    com.ib.controller.ApiController$ITopMktDataHandler
    (tickPrice [this tick-type p auto-execute?]
      ;;(println (.name tick-type) "price: " p)
      (>!! c {:sym (.symbol contract) :type (.name tick-type) :value p})
      )
    (tickSize [this tick-type size]
      ;;(println (.name tick-type) "size: " size)
      (>!! c {:sym (.symbol contract) :type (.name tick-type) :value size})
      )
    (tickString [this tick-type val]
      ;(println (.name tick-type) "LastTime: " val)
      (>!! c {:sym (.symbol contract) :type (.name tick-type) :value val})
      )
    (marketDataType [this data-type]
      (println "Frozen? " (= data-type com.ib.controller.Types$MktDataType/Frozen)))
    (tickSnapshotEnd [this] (println "tick snapshot end"))
    ))

(defn contract-ctx [c contract-def]
  (let [contract (create-contract contract-def)
        row (create-row c contract)]
    (merge contract-def {:contract contract :row row :chan c})))

(defn subscribe! [api ctx c]
  (println "subscribe to " (:contract ctx) (:contract ctx))
  (.reqTopMktData api (:contract ctx) "" false (:row ctx)))

(defn unsubscribe! [api ctx]
  (.cancelTopMktData api (:row ctx)))

(defn series-name [symbol]
  (str symbol "_ticks"))

(defn update-ticker [symbol row column value]
  (if (= column "LAST_TIMESTAMP")
              (do
                ;(influx/post-points db-client (series-name symbol) [@row])
                (println "symbol=" symbol ", " @row)
                (reset! row {:type column }))
              (swap! row assoc column value)))

(def tickers ["VXX" "SPY" "AAPL" "GOOG"])

(defn start-api! []
  (let [api (api-ctrl)]
    (.connect api "localhost" 7497 5)
    api))

(defn stop-api! [api]
  (.disconnect api))

(def tick-channel (chan))

(defn add! [api contract-def]
  (let [c tick-channel
        contract-ctx (contract-ctx c contract-def)]
    (subscribe! api contract-ctx c)
    contract-ctx
    ))

(defn remove! [api contract-ctx]
  (unsubscribe! api contract-ctx))


(defonce api (start-api!))
;(def c (:chan (add! api {:symbol "VXX" :type com.ib.controller.Types$SecType/STK})))

;(def cvxx (chan))
(def stop-chan (chan))
;(def vxx-ctx (contract-ctx cvxx {:symbol "VXX" :type com.ib.controller.Types$SecType/STK}))


(def tick-data (->> tickers
                    (map (fn [sym] [sym (atom {})]))
                    (into {})))

(defn start []
  (let [;;api (api-ctrl)
        ;;cvxx (chan)
        ;cspy (chan)
        ;vxx-ctx (contract-ctx cvxx "VXX" com.ib.controller.Types$SecType/STK)

        ]
    ;(.connect api "localhost" 7497 5)

    ;(subscribe! api vxx-ctx cvxx)
    ;(add! api {:symbol "VXX" :type com.ib.controller.Types$SecType/STK})

    ;(println "about to go-loop " tick-channel)

    ;(go-loop []
    ;  (let [[{:keys [sym type] :as msg} _] (alts! [tick-channel stop-chan])]
    ;    ;;(println "foobar")
    ;    (if (= type "LAST_TIMESTAMP") (println "***" msg) (println msg))
    ;    (if msg (recur))))


    ;(let [tick-data (->> tickers
    ;                     (map (fn [sym] [sym (atom {})]))
    ;                     (into {})) ]
    ;  )

    (go-loop []
      (let [[{:keys [sym type value] :as msg} _] (alts! [tick-channel stop-chan])]
        ;(println "just got a tick" msg ", sym=" sym  ", tick-data=" tick-data ",**** (tick-data sym)=" (tick-data sym))
        (update-ticker sym (tick-data sym) type value)
        (if msg (recur))))

    ;(Thread/sleep 100000)

    ;(unsubscribe! api vxx-ctx)
    ;(unsubscribe! api spy-ctx)

    ;(.disconnect api)
    ;(println "start function about to finish")
    ))

(comment

  (start)

  (doseq [ticker tickers]
    (add! api {:symbol ticker :type com.ib.controller.Types$SecType/STK}))



  (doseq [ctx ctxs]
    (unsubscribe! api ctx))

  ;(def c )
  (:chan (add! api {:symbol "VXX" :type com.ib.controller.Types$SecType/STK}))
  (:chan (add! api {:symbol "SPY" :type com.ib.controller.Types$SecType/STK}))
  (:chan (add! api {:symbol "AAPL" :type com.ib.controller.Types$SecType/STK}))
  (:chan (add! api {:symbol "GOOG" :type com.ib.controller.Types$SecType/STK}))




  (close! stop-chan)
  (stop-api! api)


  (tick-data "SPY")
  )


(defn -main
  "the main function"
  []
  (start))

(println "ibclj evaluated")
;(start)
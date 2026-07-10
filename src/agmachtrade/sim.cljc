(ns agmachtrade.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean, ride-on,
  engine-powered tractor order through intake -> certification
  verification -> physical dispatch (escalate/approve/commit) ->
  invoice settlement (escalate/approve/commit), then shows HARD-hold
  scenarios: a jurisdiction with no spec-basis, a counterparty whose
  credit has not been cleared, an order with no contract-terms on
  file, an engine-powered order missing its emissions certificate, a
  ride-on order missing its ROPS certificate, a counterparty that has
  not passed sanctions screening, a double dispatch, and a double
  invoice -- THEN the two type-gating control cases that prove
  `emissions-certificate-missing`/`rops-certification-missing` are
  genuinely, independently type-gated rather than a blanket
  certificate requirement: a towed implement (no engine, no ride-on
  position) dispatches CLEANLY despite neither certificate being on
  file, and a stationary/portable engine-driven implement (engine-
  powered but NOT ride-on) HARD-holds on the emissions check but NOT
  on the ROPS check.

  Like every sibling actor's domain checks, this actor's checks
  (`credit-uncleared`, `contract-missing`, `emissions-certificate-
  missing`, `rops-certification-missing`,
  `counterparty-sanctions-flag-unresolved`) are evaluated directly at
  `:delivery/dispatch` rather than via a separate screening op -- a
  real dispatch decision validates counterparty credit, contract-on-
  file, emissions certification, ROPS certification and sanctions
  screening at the point of the act itself, not as a discrete pre-
  screening ceremony. Each check is still exercised directly and
  independently below, one order per HARD-hold scenario, following the
  SAME 'exercise the failure mode directly, never only via a happy-path
  actuation' discipline every sibling since `parksafety`'s
  ADR-2607071922 Decision 5 establishes."
  (:require [langgraph.graph :as g]
            [agmachtrade.store :as store]
            [agmachtrade.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :trading-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== order/intake eo-1 (USA tractor, clean) ==")
    (println (exec-op actor "t1" {:op :order/intake :subject "eo-1"
                                  :patch {:id "eo-1" :counterparty "Akita Agri Machinery Trading Co"}} operator))

    (println "== certification/verify eo-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :certification/verify :subject "eo-1"} operator))
    (println (approve! actor "t2"))

    (println "== delivery/dispatch eo-1 (always escalates -- :delivery/dispatch) ==")
    (let [r (exec-op actor "t3" {:op :delivery/dispatch :subject "eo-1"} operator)]
      (println r)
      (println "-- human trading supervisor approves --")
      (println (approve! actor "t3")))

    (println "== invoice/settle eo-1 (always escalates -- :invoice/settle) ==")
    (let [r (exec-op actor "t4" {:op :invoice/settle :subject "eo-1"} operator)]
      (println r)
      (println "-- human trading supervisor approves --")
      (println (approve! actor "t4")))

    (println "== certification/verify eo-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :certification/verify :subject "eo-2"} operator))

    (println "== certification/verify eo-3 (escalates -- human approves; sets up the credit-uncleared test) ==")
    (println (exec-op actor "t6" {:op :certification/verify :subject "eo-3"} operator))
    (println (approve! actor "t6"))

    (println "== delivery/dispatch eo-3 (credit not cleared -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :delivery/dispatch :subject "eo-3"} operator))

    (println "== certification/verify eo-4 (escalates -- human approves; sets up the contract-missing test) ==")
    (println (exec-op actor "t8" {:op :certification/verify :subject "eo-4"} operator))
    (println (approve! actor "t8"))

    (println "== delivery/dispatch eo-4 (no contract-terms on file -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :delivery/dispatch :subject "eo-4"} operator))

    (println "== certification/verify eo-5 (escalates -- human approves; sets up the sanctions test) ==")
    (println (exec-op actor "t10" {:op :certification/verify :subject "eo-5"} operator))
    (println (approve! actor "t10"))

    (println "== delivery/dispatch eo-5 (sanctions screening not passed -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :delivery/dispatch :subject "eo-5"} operator))

    (println "== certification/verify eo-6 (escalates -- human approves; sets up the emissions-certificate-missing test) ==")
    (println (exec-op actor "t12" {:op :certification/verify :subject "eo-6"} operator))
    (println (approve! actor "t12"))

    (println "== delivery/dispatch eo-6 (engine-powered, no emissions certificate on file -> HARD hold) ==")
    (println (exec-op actor "t13" {:op :delivery/dispatch :subject "eo-6"} operator))

    (println "== certification/verify eo-7 (escalates -- human approves; sets up the rops-certification-missing test) ==")
    (println (exec-op actor "t14" {:op :certification/verify :subject "eo-7"} operator))
    (println (approve! actor "t14"))

    (println "== delivery/dispatch eo-7 (ride-on, no ROPS certificate on file -> HARD hold, a DIFFERENT rule from eo-6) ==")
    (println (exec-op actor "t15" {:op :delivery/dispatch :subject "eo-7"} operator))

    (println "== certification/verify eo-8 (towed implement -- escalates -- human approves) ==")
    (println (exec-op actor "t16" {:op :certification/verify :subject "eo-8"} operator))
    (println (approve! actor "t16"))

    (println "== delivery/dispatch eo-8 (towed implement: no engine, no ride-on position, NEITHER cert on file -> NO-OP for BOTH checks, escalates on actuation only) ==")
    (let [r (exec-op actor "t17" {:op :delivery/dispatch :subject "eo-8"} operator)]
      (println r)
      (println "-- human trading supervisor approves (both certification checks were true NO-OPs, not silently forgiven) --")
      (println (approve! actor "t17")))

    (println "== certification/verify eo-9 (stationary engine-driven implement -- escalates -- human approves) ==")
    (println (exec-op actor "t18" {:op :certification/verify :subject "eo-9"} operator))
    (println (approve! actor "t18"))

    (println "== delivery/dispatch eo-9 (engine-powered but NOT ride-on: emissions-certificate-missing fires, rops-certification-missing does NOT -> proves independent type-gating) ==")
    (println (exec-op actor "t19" {:op :delivery/dispatch :subject "eo-9"} operator))

    (println "== delivery/dispatch eo-1 AGAIN (double-dispatch -> HARD hold) ==")
    (println (exec-op actor "t20" {:op :delivery/dispatch :subject "eo-1"} operator))

    (println "== invoice/settle eo-1 AGAIN (double-invoice -> HARD hold) ==")
    (println (exec-op actor "t21" {:op :invoice/settle :subject "eo-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft equipment-dispatch records ==")
    (doseq [r (store/dispatch-history db)] (println r))

    (println "== draft equipment-invoice records ==")
    (doseq [r (store/invoice-history db)] (println r))))

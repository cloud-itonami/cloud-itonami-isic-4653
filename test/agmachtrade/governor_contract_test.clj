(ns agmachtrade.governor-contract-test
  "The governor contract as executable tests. The single invariant
  under test:

    AgMachTradeAdvisor never dispatches physical farm machinery to a
    counterparty or settles an invoice the Ag Equipment Governor would
    reject, `:delivery/dispatch`/`:invoice/settle` NEVER auto-commit at
    any phase, `:order/intake` (no direct capital risk) MAY auto-commit
    when clean, and every decision (commit OR hold) leaves exactly one
    ledger fact.

  PLUS the vertical's own defining proof: `emissions-certificate-
  missing` and `rops-certification-missing` are TWO genuinely
  independently type-gated checks, not one blanket certificate
  requirement -- `towed-implement-is-a-no-op-for-both-certification-
  checks` and `stationary-engine-unit-proves-independent-type-gating`
  below prove this end-to-end."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [agmachtrade.store :as store]
            [agmachtrade.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :trading-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through certification verify -> approve, leaving a
  certification assessment on file. Uses distinct thread-ids per call
  site by suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :certification/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :order/intake :subject "eo-1"
                   :patch {:id "eo-1" :counterparty "Akita Agri Machinery Trading Co"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Akita Agri Machinery Trading Co" (:counterparty (store/equipment-order db "eo-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest certification-verify-always-needs-approval
  (testing "certification verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :certification/verify :subject "eo-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "eo-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a certification/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :certification/verify :subject "eo-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "eo-2")) "no assessment written"))))

(deftest dispatch-without-assessment-is-held
  (testing "delivery/dispatch before any certification verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :delivery/dispatch :subject "eo-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest credit-uncleared-is-held-and-unoverridable
  (testing "a counterparty whose credit has not been cleared -> HOLD, and never reaches request-approval -- the leasing collateral-coverage discipline applied to counterparty credit"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "eo-3")
          res (exec-op actor "t5" {:op :delivery/dispatch :subject "eo-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:credit-uncleared} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest contract-missing-is-held-and-unoverridable
  (testing "an order with no contract-terms on file -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          _ (verify! actor "t6pre" "eo-4")
          res (exec-op actor "t6" {:op :delivery/dispatch :subject "eo-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:contract-missing} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest counterparty-sanctions-flag-unresolved-is-held-and-unoverridable
  (testing "a counterparty that has not passed OFAC / equivalent sanctions screening -> HOLD, and never reaches request-approval (evaluated at both dispatch and invoice)"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "eo-5")
          res (exec-op actor "t7" {:op :delivery/dispatch :subject "eo-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:counterparty-sanctions-flag-unresolved} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest emissions-certificate-missing-is-held-and-unoverridable
  (testing "an engine-powered order with no emissions certificate on file -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "eo-6")
          res (exec-op actor "t8" {:op :delivery/dispatch :subject "eo-6"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:emissions-certificate-missing} (-> (store/ledger db) last :basis)))
      (is (not (some #{:rops-certification-missing} (-> (store/ledger db) last :basis)))
          "eo-6 has a valid ROPS certificate on file -- the ROPS check must NOT also fire")
      (is (empty? (store/dispatch-history db))))))

(deftest rops-certification-missing-is-a-genuinely-different-failure-mode-from-emissions
  (testing "a ride-on order with no ROPS certificate on file -> HOLD on a DIFFERENT rule from emissions-certificate-missing"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "eo-7")
          res (exec-op actor "t9" {:op :delivery/dispatch :subject "eo-7"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:rops-certification-missing} (-> (store/ledger db) last :basis)))
      (is (not (some #{:emissions-certificate-missing} (-> (store/ledger db) last :basis)))
          "eo-7 has a valid emissions certificate on file -- the emissions check must NOT also fire")
      (is (empty? (store/dispatch-history db))))))

(deftest towed-implement-is-a-no-op-for-both-certification-checks
  (testing "eo-8 (towed implement: no engine, no ride-on position, NEITHER certificate on file) dispatches CLEANLY -- proving genuine type-gating, not a blanket certificate requirement"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "eo-8")
          r1 (exec-op actor "t10" {:op :delivery/dispatch :subject "eo-8"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval on actuation grounds ONLY -- no HARD hold")
      (let [r2 (approve! actor "t10")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:dispatched? (store/equipment-order db "eo-8"))))
        (is (= 1 (count (store/dispatch-history db))) "one draft dispatch record")))))

(deftest stationary-engine-unit-proves-independent-type-gating
  (testing "eo-9 (stationary engine-driven implement: engine-powered but NOT ride-on) HARD-holds on emissions-certificate-missing but NOT rops-certification-missing -- proving the two checks are gated on INDEPENDENT properties, not two faces of one commodity-type distinction"
    (let [[db actor] (fresh)
          _ (verify! actor "t11pre" "eo-9")
          res (exec-op actor "t11" {:op :delivery/dispatch :subject "eo-9"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:emissions-certificate-missing} (-> (store/ledger db) last :basis))
          "eo-9 is engine-powered with no emissions certificate -- this check MUST fire")
      (is (not (some #{:rops-certification-missing} (-> (store/ledger db) last :basis)))
          "eo-9 is NOT ride-on -- there is no rollover hazard, so this check must NOT fire even though its own :rops-certified? fact is false")
      (is (empty? (store/dispatch-history db))))))

(deftest delivery-dispatch-always-escalates-then-human-decides
  (testing "a clean, fully-verified, credit-cleared, contract-on-file, emissions-certified, rops-certified, sanctions-screened order still ALWAYS interrupts for human approval -- :delivery/dispatch is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t12pre" "eo-1")
          r1 (exec-op actor "t12" {:op :delivery/dispatch :subject "eo-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, dispatch record drafted"
        (let [r2 (approve! actor "t12")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:dispatched? (store/equipment-order db "eo-1"))))
          (is (= 1 (count (store/dispatch-history db))) "one draft dispatch record"))))))

(deftest invoice-settle-always-escalates-then-human-decides
  (testing "a clean, fully-verified, already-dispatched order still ALWAYS interrupts for human approval -- :invoice/settle is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t13pre" "eo-1")
          _ (exec-op actor "t13dispatch" {:op :delivery/dispatch :subject "eo-1"} operator)
          _ (approve! actor "t13dispatch")
          r1 (exec-op actor "t13" {:op :invoice/settle :subject "eo-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, invoice record drafted"
        (let [r2 (approve! actor "t13")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:invoiced? (store/equipment-order db "eo-1"))))
          (is (= 1 (count (store/invoice-history db))) "one draft invoice record"))))))

(deftest delivery-dispatch-double-dispatch-is-held
  (testing "dispatching the same equipment-order twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t14pre" "eo-1")
          _ (exec-op actor "t14a" {:op :delivery/dispatch :subject "eo-1"} operator)
          _ (approve! actor "t14a")
          res (exec-op actor "t14" {:op :delivery/dispatch :subject "eo-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispatched} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/dispatch-history db))) "still only the one earlier dispatch"))))

(deftest invoice-settle-double-invoice-is-held
  (testing "settling the same equipment-order's invoice twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t15pre" "eo-1")
          _ (exec-op actor "t15dispatch" {:op :delivery/dispatch :subject "eo-1"} operator)
          _ (approve! actor "t15dispatch")
          _ (exec-op actor "t15a" {:op :invoice/settle :subject "eo-1"} operator)
          _ (approve! actor "t15a")
          res (exec-op actor "t15" {:op :invoice/settle :subject "eo-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-invoiced} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/invoice-history db))) "still only the one earlier invoice"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :order/intake :subject "eo-1"
                          :patch {:id "eo-1" :counterparty "Akita Agri Machinery Trading Co"}} operator)
      (exec-op actor "b" {:op :certification/verify :subject "eo-2"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

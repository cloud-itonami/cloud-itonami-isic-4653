(ns agmachtrade.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [agmachtrade.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "USA" (:jurisdiction (store/equipment-order s "eo-1"))))
      (is (= "Akita Agri Machinery Trading Co" (:counterparty (store/equipment-order s "eo-1"))))
      (is (= :tractor (:equipment-type (store/equipment-order s "eo-1"))))
      (is (= "ATL" (:jurisdiction (store/equipment-order s "eo-2"))))
      (is (false? (:credit-cleared? (store/equipment-order s "eo-3"))) "eo-3 credit not cleared")
      (is (nil? (:contract-terms (store/equipment-order s "eo-4"))) "eo-4 no contract-terms")
      (is (false? (:sanctions-screened? (store/equipment-order s "eo-5"))) "eo-5 sanctions not screened")
      (is (false? (:emissions-certificate? (store/equipment-order s "eo-6"))) "eo-6 no emissions certificate")
      (is (false? (:rops-certified? (store/equipment-order s "eo-7"))) "eo-7 no ROPS certificate")
      (is (false? (:engine-powered? (store/equipment-order s "eo-8"))) "eo-8 towed implement has no engine")
      (is (false? (:ride-on? (store/equipment-order s "eo-8"))) "eo-8 towed implement has no ride-on position")
      (is (true? (:engine-powered? (store/equipment-order s "eo-9"))) "eo-9 stationary unit has an engine")
      (is (false? (:ride-on? (store/equipment-order s "eo-9"))) "eo-9 stationary unit has no ride-on position")
      (is (false? (:dispatched? (store/equipment-order s "eo-1"))))
      (is (false? (:invoiced? (store/equipment-order s "eo-1"))))
      (is (= ["eo-1" "eo-2" "eo-3" "eo-4" "eo-5" "eo-6" "eo-7" "eo-8" "eo-9"]
             (mapv :id (store/all-equipment-orders s))))
      (is (nil? (store/assessment-of s "eo-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/dispatch-history s)))
      (is (= [] (store/invoice-history s)))
      (is (zero? (store/next-dispatch-sequence s "USA")))
      (is (zero? (store/next-invoice-sequence s "USA")))
      (is (false? (store/equipment-order-already-dispatched? s "eo-1")))
      (is (false? (store/equipment-order-already-invoiced? s "eo-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :order/upsert
                                 :value {:id "eo-1" :counterparty "Akita Agri Machinery Trading Co"}})
        (is (= "Akita Agri Machinery Trading Co" (:counterparty (store/equipment-order s "eo-1"))))
        (is (= "USA" (:jurisdiction (store/equipment-order s "eo-1"))) "unrelated field preserved"))
      (testing "certification-assessment payloads commit and read back"
        (store/commit-record! s {:effect :certification-assessment/set :path ["eo-1"]
                                 :payload {:jurisdiction "USA" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "USA" :checklist ["a" "b"]} (store/assessment-of s "eo-1"))))
      (testing "equipment dispatch drafts a record and advances the dispatch sequence"
        (store/commit-record! s {:effect :order/mark-dispatched :path ["eo-1"]})
        (is (= "USA-DISPATCH-000000" (get (first (store/dispatch-history s)) "record_id")))
        (is (= "equipment-dispatch-draft" (get (first (store/dispatch-history s)) "kind")))
        (is (true? (:dispatched? (store/equipment-order s "eo-1"))))
        (is (= 1 (count (store/dispatch-history s))))
        (is (= 1 (store/next-dispatch-sequence s "USA")))
        (is (true? (store/equipment-order-already-dispatched? s "eo-1"))))
      (testing "invoice settlement drafts a record and advances the invoice sequence"
        (store/commit-record! s {:effect :order/mark-invoiced :path ["eo-1"]})
        (is (= "USA-INVOICE-000000" (get (first (store/invoice-history s)) "record_id")))
        (is (= "equipment-invoice-draft" (get (first (store/invoice-history s)) "kind")))
        (is (true? (:invoiced? (store/equipment-order s "eo-1"))))
        (is (= 1 (count (store/invoice-history s))))
        (is (= 1 (store/next-invoice-sequence s "USA")))
        (is (true? (store/equipment-order-already-invoiced? s "eo-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/equipment-order s "nope")))
    (is (= [] (store/all-equipment-orders s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/dispatch-history s)))
    (is (= [] (store/invoice-history s)))
    (is (zero? (store/next-dispatch-sequence s "USA")))
    (is (zero? (store/next-invoice-sequence s "USA")))
    (store/with-equipment-orders s {"x" {:id "x" :order-id "EO-X" :equipment-type :tractor
                                         :engine-powered? true :ride-on? true
                                         :counterparty "c" :price 62000.00
                                         :contract-terms "FOB dealership yard, net 30 days"
                                         :credit-cleared? true :sanctions-screened? true
                                         :emissions-certificate? true :rops-certified? true
                                         :dispatched? false :invoiced? false
                                         :jurisdiction "USA" :status :intake
                                         :dispatch-number nil :invoice-number nil}})
    (is (= "c" (:counterparty (store/equipment-order s "x"))))))

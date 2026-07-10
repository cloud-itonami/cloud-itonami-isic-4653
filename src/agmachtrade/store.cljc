(ns agmachtrade.store
  "SSoT for the agricultural-machinery-wholesale actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/agmachtrade/store_contract_test.clj), which is the whole point:
  the actor, the Ag Equipment Governor and the audit ledger never know
  which SSoT they run on.

  Like the fuel-wholesale sibling's own `fuel-order` entity, this
  vertical's `dispatch` and `settle` actuation events apply SEQUENTIALLY
  to the SAME `equipment-order` -- physical dispatch happens first
  (machinery leaves the wholesale yard/dealership), invoice settlement
  happens later, on the same order record. This matches the sequential
  dual-actuation shape, with dedicated double-actuation-guard booleans
  (`:dispatched?`/`:invoiced?`, never a `:status` value).

  The `equipment-order` record carries TWO INDEPENDENT boolean facts
  the Ag Equipment Governor's own defining checks gate on --
  `:engine-powered?` (does this machine carry an engine subject to
  nonroad emissions standards at all) and `:ride-on?` (is this an
  operator-ride machine where rollover is a real hazard) -- plus the
  two certificate facts those gates protect, `:emissions-certificate?`
  and `:rops-certified?`. See `agmachtrade.governor` namespace docstring
  for why these are two SEPARATE, independently-gated facts rather than
  one commodity-type enum.

  The ledger stays append-only on every backend: 'which equipment-order
  was verified for a jurisdiction with no official spec-basis, which
  counterparty had credit-uncleared / no contract / a missing emissions
  certificate / a missing ROPS certificate / an unresolved sanctions-
  screening flag, which order was dispatched, which invoice was
  settled, on what jurisdictional basis, approved by whom' is always a
  query over an immutable log -- the audit trail a regulator, a
  counterparty, or an operator trusting an ag-machinery-wholesale actor
  needs, and the evidence an operator needs if a dispatch or an invoice
  is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [agmachtrade.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (equipment-order [s id])
  (all-equipment-orders [s])
  (assessment-of [s equipment-order-id] "committed certification assessment, or nil")
  (ledger [s])
  (dispatch-history [s] "the append-only equipment-dispatch history (agmachtrade.registry drafts)")
  (invoice-history [s] "the append-only equipment-invoice history (agmachtrade.registry drafts)")
  (next-dispatch-sequence [s jurisdiction] "next dispatch-number sequence for a jurisdiction")
  (next-invoice-sequence [s jurisdiction] "next invoice-number sequence for a jurisdiction")
  (equipment-order-already-dispatched? [s equipment-order-id] "has this equipment already been dispatched?")
  (equipment-order-already-invoiced? [s equipment-order-id] "has this order's invoice already been settled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-equipment-orders [s equipment-orders] "replace/seed the equipment-order directory (map id->equipment-order)"))

;; ----------------------------- demo data -----------------------------

(defn- base-order
  "The neutral, clean equipment-order shape (every field in its safe
  state), so each demo order below isolates exactly ONE failure mode by
  overriding a single field. The base shape is a ride-on, engine-powered
  tractor -- both certification gates apply and both are clean."
  [overrides]
  (merge {:id "eo-1" :order-id "EO-2026-0001" :equipment-type :tractor
          :engine-powered? true :ride-on? true
          :counterparty "Akita Agri Machinery Trading Co"
          :price 62000.00 :contract-terms "FOB dealership yard, net 30 days"
          :credit-cleared? true :sanctions-screened? true
          :emissions-certificate? true :rops-certified? true
          :dispatched? false :invoiced? false
          :jurisdiction "USA" :status :intake
          :dispatch-number nil :invoice-number nil}
         overrides))

(defn demo-data
  "A small, self-contained equipment-order set covering both actuation
  lifecycles (dispatch, invoice settlement), the Ag Equipment Governor's
  own generic checks, AND -- the defining proof of this vertical --
  TWO control pairs showing the emissions/ROPS certification checks are
  genuinely, independently type-gated rather than a blanket
  certificate requirement:

    - `eo-8` (towed implement: `:engine-powered?` false, `:ride-on?`
      false, NEITHER certificate on file) dispatches CLEANLY -- proving
      BOTH checks are true NO-OPs for a non-engine, non-ride-on
      implement, not merely 'missing certs, but forgiven'.
    - `eo-9` (stationary/portable engine-driven implement:
      `:engine-powered?` true, `:ride-on?` false, NEITHER certificate on
      file) HARD-holds on `:emissions-certificate-missing` but NOT on
      `:rops-certification-missing` -- proving the two gates fire
      INDEPENDENTLY of each other, not as two symptoms of one
      underlying commodity-type distinction (`eo-8` alone could not
      prove this: a reader could otherwise suspect the two checks are
      secretly the same 'is this a towed implement?' test)."
  []
  {:equipment-orders
   (into {}
         (for [o [(base-order {:id "eo-1" :order-id "EO-2026-0001"})
                  (base-order {:id "eo-2" :order-id "EO-2026-0002"
                               :counterparty "Atlantis Farm Equipment Ltd"
                               :jurisdiction "ATL"})
                  (base-order {:id "eo-3" :order-id "EO-2026-0003"
                               :counterparty "Cedar Combine Traders"
                               :credit-cleared? false})
                  (base-order {:id "eo-4" :order-id "EO-2026-0004"
                               :counterparty "Delta Tractor Distributors BV"
                               :contract-terms nil})
                  (base-order {:id "eo-5" :order-id "EO-2026-0005"
                               :counterparty "Eagle Ag Equipment SA"
                               :sanctions-screened? false})
                  (base-order {:id "eo-6" :order-id "EO-2026-0006"
                               :counterparty "Fenwick Row-Crop Tractors Inc"
                               :emissions-certificate? false})
                  (base-order {:id "eo-7" :order-id "EO-2026-0007"
                               :counterparty "Granger Combine Harvesters KK"
                               :rops-certified? false})
                  (base-order {:id "eo-8" :order-id "EO-2026-0008"
                               :equipment-type :towed-implement
                               :counterparty "Harrow & Disc Implement Co"
                               :engine-powered? false :ride-on? false
                               :emissions-certificate? false :rops-certified? false})
                  (base-order {:id "eo-9" :order-id "EO-2026-0009"
                               :equipment-type :stationary-engine-unit
                               :counterparty "Ironwood Irrigation Pump Units Ltd"
                               :engine-powered? true :ride-on? false
                               :emissions-certificate? false :rops-certified? false})]]
           [(:id o) o]))})

;; ----------------------------- shared commit logic -----------------------------

(defn- dispatch-order!
  "Backend-agnostic `:order/mark-dispatched` -- looks up the equipment-
  order via the protocol and drafts the equipment-dispatch record, and
  returns {:result .. :equipment-order-patch ..} for the caller to
  persist."
  [s equipment-order-id]
  (let [eo (equipment-order s equipment-order-id)
        seq-n (next-dispatch-sequence s (:jurisdiction eo))
        result (registry/register-dispatch-record equipment-order-id (:jurisdiction eo) seq-n)]
    {:result result
     :equipment-order-patch {:dispatched? true
                             :dispatch-number (get result "dispatch_number")}}))

(defn- invoice-order!
  "Backend-agnostic `:order/mark-invoiced` -- looks up the equipment-
  order via the protocol and drafts the equipment-invoice record, and
  returns {:result .. :equipment-order-patch ..} for the caller to
  persist."
  [s equipment-order-id]
  (let [eo (equipment-order s equipment-order-id)
        seq-n (next-invoice-sequence s (:jurisdiction eo))
        result (registry/register-invoice-record equipment-order-id (:jurisdiction eo) seq-n)]
    {:result result
     :equipment-order-patch {:invoiced? true
                             :invoice-number (get result "invoice_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (equipment-order [_ id] (get-in @a [:equipment-orders id]))
  (all-equipment-orders [_] (sort-by :id (vals (:equipment-orders @a))))
  (assessment-of [_ equipment-order-id] (get-in @a [:assessments equipment-order-id]))
  (ledger [_] (:ledger @a))
  (dispatch-history [_] (:dispatches @a))
  (invoice-history [_] (:invoices @a))
  (next-dispatch-sequence [_ jurisdiction] (get-in @a [:dispatch-sequences jurisdiction] 0))
  (next-invoice-sequence [_ jurisdiction] (get-in @a [:invoice-sequences jurisdiction] 0))
  (equipment-order-already-dispatched? [_ equipment-order-id] (boolean (get-in @a [:equipment-orders equipment-order-id :dispatched?])))
  (equipment-order-already-invoiced? [_ equipment-order-id] (boolean (get-in @a [:equipment-orders equipment-order-id :invoiced?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :order/upsert
      (swap! a update-in [:equipment-orders (:id value)] merge value)

      :certification-assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :order/mark-dispatched
      (let [equipment-order-id (first path)
            {:keys [result equipment-order-patch]} (dispatch-order! s equipment-order-id)
            jurisdiction (:jurisdiction (equipment-order s equipment-order-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:dispatch-sequences jurisdiction] (fnil inc 0))
                       (update-in [:equipment-orders equipment-order-id] merge equipment-order-patch)
                       (update :dispatches registry/append result))))
        result)

      :order/mark-invoiced
      (let [equipment-order-id (first path)
            {:keys [result equipment-order-patch]} (invoice-order! s equipment-order-id)
            jurisdiction (:jurisdiction (equipment-order s equipment-order-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:invoice-sequences jurisdiction] (fnil inc 0))
                       (update-in [:equipment-orders equipment-order-id] merge equipment-order-patch)
                       (update :invoices registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-equipment-orders [s equipment-orders] (when (seq equipment-orders) (swap! a assoc :equipment-orders equipment-orders)) s))

(defn seed-db
  "A MemStore seeded with the demo equipment-order set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :dispatch-sequences {} :dispatches []
                           :invoice-sequences {} :invoices []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts, dispatch/
  invoice records) are stored as EDN strings so `langchain.db` doesn't
  expand them into sub-entities -- the same convention every sibling
  actor's store uses."
  {:equipment-order/id                   {:db/unique :db.unique/identity}
   :assessment/equipment-order-id        {:db/unique :db.unique/identity}
   :ledger/seq                           {:db/unique :db.unique/identity}
   :dispatch/seq                         {:db/unique :db.unique/identity}
   :invoice/seq                          {:db/unique :db.unique/identity}
   :dispatch-sequence/jurisdiction       {:db/unique :db.unique/identity}
   :invoice-sequence/jurisdiction        {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; Every equipment-order field is stored as its own Datomic attr so a
;; governor pull reads the exact ground truth (no blob decode). Boolean
;; fields are coerced on read so a missing attr reads back as false
;; (parity with MemStore). [field-key tx-attr boolean?]
(def ^:private equipment-order-fields
  [[:id :equipment-order/id false]
   [:order-id :equipment-order/order-id false]
   [:equipment-type :equipment-order/equipment-type false]
   [:engine-powered? :equipment-order/engine-powered? true]
   [:ride-on? :equipment-order/ride-on? true]
   [:counterparty :equipment-order/counterparty false]
   [:price :equipment-order/price false]
   [:contract-terms :equipment-order/contract-terms false]
   [:credit-cleared? :equipment-order/credit-cleared? true]
   [:sanctions-screened? :equipment-order/sanctions-screened? true]
   [:emissions-certificate? :equipment-order/emissions-certificate? true]
   [:rops-certified? :equipment-order/rops-certified? true]
   [:dispatched? :equipment-order/dispatched? true]
   [:invoiced? :equipment-order/invoiced? true]
   [:jurisdiction :equipment-order/jurisdiction false]
   [:status :equipment-order/status false]
   [:dispatch-number :equipment-order/dispatch-number false]
   [:invoice-number :equipment-order/invoice-number false]])

(defn- equipment-order->tx [eo]
  (reduce (fn [tx [k attr _bool?]]
            (let [v (get eo k)]
              (cond-> tx (some? v) (assoc attr v))))
          {:equipment-order/id (:id eo)}
          equipment-order-fields))

(def ^:private equipment-order-pull (mapv second equipment-order-fields))

(defn- pull->equipment-order [m]
  (when (:equipment-order/id m)
    (reduce (fn [eo [k attr bool?]]
              (let [v (get m attr)]
                (cond
                  bool?        (assoc eo k (boolean v))
                  (some? v)    (assoc eo k v)
                  :else        eo)))
            {:id (:equipment-order/id m)}
            equipment-order-fields)))

(defrecord DatomicStore [conn]
  Store
  (equipment-order [_ id]
    (pull->equipment-order (d/pull (d/db conn) equipment-order-pull [:equipment-order/id id])))
  (all-equipment-orders [_]
    (->> (d/q '[:find [?id ...] :where [?e :equipment-order/id ?id]] (d/db conn))
         (map #(pull->equipment-order (d/pull (d/db conn) equipment-order-pull [:equipment-order/id %])))
         (sort-by :id)))
  (assessment-of [_ equipment-order-id]
    (dec* (d/q '[:find ?p . :in $ ?eoid
                :where [?a :assessment/equipment-order-id ?eoid] [?a :assessment/payload ?p]]
              (d/db conn) equipment-order-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (dispatch-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :dispatch/seq ?s] [?e :dispatch/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (invoice-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :invoice/seq ?s] [?e :invoice/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-dispatch-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :dispatch-sequence/jurisdiction ?j] [?e :dispatch-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-invoice-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :invoice-sequence/jurisdiction ?j] [?e :invoice-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (equipment-order-already-dispatched? [s equipment-order-id]
    (boolean (:dispatched? (equipment-order s equipment-order-id))))
  (equipment-order-already-invoiced? [s equipment-order-id]
    (boolean (:invoiced? (equipment-order s equipment-order-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :order/upsert
      (d/transact! conn [(equipment-order->tx value)])

      :certification-assessment/set
      (d/transact! conn [{:assessment/equipment-order-id (first path) :assessment/payload (enc payload)}])

      :order/mark-dispatched
      (let [equipment-order-id (first path)
            {:keys [result equipment-order-patch]} (dispatch-order! s equipment-order-id)
            jurisdiction (:jurisdiction (equipment-order s equipment-order-id))
            next-n (inc (next-dispatch-sequence s jurisdiction))]
        (d/transact! conn
                     [(equipment-order->tx (assoc equipment-order-patch :id equipment-order-id))
                      {:dispatch-sequence/jurisdiction jurisdiction :dispatch-sequence/next next-n}
                      {:dispatch/seq (count (dispatch-history s)) :dispatch/record (enc (get result "record"))}])
        result)

      :order/mark-invoiced
      (let [equipment-order-id (first path)
            {:keys [result equipment-order-patch]} (invoice-order! s equipment-order-id)
            jurisdiction (:jurisdiction (equipment-order s equipment-order-id))
            next-n (inc (next-invoice-sequence s jurisdiction))]
        (d/transact! conn
                     [(equipment-order->tx (assoc equipment-order-patch :id equipment-order-id))
                      {:invoice-sequence/jurisdiction jurisdiction :invoice-sequence/next next-n}
                      {:invoice/seq (count (invoice-history s)) :invoice/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-equipment-orders [s equipment-orders]
    (when (seq equipment-orders) (d/transact! conn (mapv equipment-order->tx (vals equipment-orders)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:equipment-orders ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [equipment-orders]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-equipment-orders s equipment-orders))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo equipment-order set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

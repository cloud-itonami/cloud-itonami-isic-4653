(ns agmachtrade.registry
  "Pure-function equipment-dispatch + equipment-invoice record
  construction -- an append-only ag-machinery-wholesale book-of-record
  draft.

  Like the fuel-wholesale sibling's own registry, this ag-machinery-
  wholesale vertical's Ag Equipment Governor needs NO registry range-
  check functions at all: its domain checks (credit-uncleared,
  contract-missing, emissions-certificate-missing, rops-certification-
  missing, counterparty-sanctions-flag-unresolved) are direct entity
  boolean reads in `agmachtrade.governor`, off dedicated
  `:credit-cleared?` / `:contract-terms` / `:emissions-certificate?` /
  `:rops-certified?` / `:sanctions-screened?` facts on the
  `equipment-order` record. So this namespace is RECORD CONSTRUCTION
  ONLY -- no pure range checks to host here.

  Like every sibling actor's registry, there is no single international
  reference-number standard for an equipment-dispatch or equipment-
  invoice record -- every operator/jurisdiction assigns its own
  reference format. This namespace does NOT invent one beyond a
  jurisdiction-scoped sequence number; it validates the record's
  required fields, the same honest, non-fabricating discipline
  `agmachtrade.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real dealership/ERP/billing system. It builds the RECORD
  an operator would keep, not the act of dispatching real physical farm
  machinery from the wholesale yard or settling a real invoice itself
  (that is `agmachtrade.operation`'s `:delivery/dispatch`/`:invoice/
  settle`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ----------------------------- record construction -----------------------------

(defn register-dispatch-record
  "Validate + construct the EQUIPMENT-DISPATCH registration DRAFT -- the
  operator's own legal act of dispatching real physical farm machinery
  from the wholesale yard/dealership to a counterparty. Pure function --
  does not touch any real yard/logistics/ERP system; it builds the
  RECORD an operator would keep. `agmachtrade.governor` independently
  re-verifies the counterparty's credit-clearance, contract-on-file,
  emissions-certificate, ROPS-certificate and evidence-completeness
  ground truth, and blocks a double-dispatch of the same
  equipment-order, before this is ever allowed to commit."
  [equipment-order-id jurisdiction sequence]
  (when-not (and equipment-order-id (not= equipment-order-id ""))
    (throw (ex-info "equipment-dispatch: equipment_order_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "equipment-dispatch: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "equipment-dispatch: sequence must be >= 0" {})))
  (let [dispatch-number (str (str/upper-case jurisdiction) "-DISPATCH-" (zero-pad sequence 6))
        record {"record_id" dispatch-number
                "kind" "equipment-dispatch-draft"
                "equipment_order_id" equipment-order-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "dispatch_number" dispatch-number
     "certificate" (unsigned-certificate "EquipmentDispatch" dispatch-number dispatch-number)}))

(defn register-invoice-record
  "Validate + construct the EQUIPMENT-INVOICE registration DRAFT -- the
  operator's own legal act of settling a real ag-machinery invoice (the
  money side of a wholesale trade, custody/financial transfer). Pure
  function -- does not touch any real billing or accounts-receivable
  system; it builds the RECORD an operator would keep.
  `agmachtrade.governor` independently re-verifies the sanctions-
  screening and evidence-completeness ground truth, and blocks a
  double-invoice of the same equipment-order, before this is ever
  allowed to commit."
  [equipment-order-id jurisdiction sequence]
  (when-not (and equipment-order-id (not= equipment-order-id ""))
    (throw (ex-info "equipment-invoice: equipment_order_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "equipment-invoice: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "equipment-invoice: sequence must be >= 0" {})))
  (let [invoice-number (str (str/upper-case jurisdiction) "-INVOICE-" (zero-pad sequence 6))
        record {"record_id" invoice-number
                "kind" "equipment-invoice-draft"
                "equipment_order_id" equipment-order-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "invoice_number" invoice-number
     "certificate" (unsigned-certificate "EquipmentInvoice" invoice-number invoice-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))

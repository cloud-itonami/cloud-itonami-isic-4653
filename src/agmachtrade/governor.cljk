(ns agmachtrade.governor
  "Ag Equipment Governor -- the independent compliance layer that earns
  the AgMachTradeAdvisor the right to commit. The LLM has no notion of
  jurisdictional agricultural-machinery-trade law, whether a
  counterparty's credit has actually been cleared, whether contract
  terms are actually on file, whether THIS specific machine actually
  has a valid, currently-effective EPA/EU nonroad-engine emissions
  certificate on file, whether THIS specific machine actually has a
  valid ROPS (Roll-Over Protective Structure) safety certificate on
  file, whether OFAC / equivalent sanctions screening has actually been
  passed, or when an act stops being a draft and becomes a real
  dispatch of physical farm machinery or a real invoice settlement, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  Like every principal-trading sibling's own governor, this
  agricultural-machinery-wholesale vertical has NO pre-existing
  ag-machinery-trading capability library to delegate to -- so the
  domain checks (credit-clearance, contract-on-file, emissions
  certification, ROPS certification, sanctions-screening) are direct
  entity boolean reads off the `equipment-order` record, evaluated
  directly here, NOT delegated to a separate library's validated
  function.

  `:itonami.blueprint/governor` is `:ag-equipment-governor`, grep-
  verified UNIQUE fleet-wide -- no naming-collision precedent question,
  a fresh independent build following the SAME governed-actor
  architecture (langgraph StateGraph + independent Governor + Phase
  0->3 rollout) established by `cloud-itonami-isic-6511` and applied by
  the fuel-wholesale (`cloud-itonami-isic-4671`), metal-wholesale
  (`cloud-itonami-isic-4662`), agri-raw-materials-wholesale
  (`cloud-itonami-isic-4620`) and computer/tech-wholesale
  (`cloud-itonami-isic-4651`) siblings.

  CRITICAL STRUCTURAL DIFFERENCE from every prior wholesale-trading
  sibling: this vertical's controlled event is fundamentally different
  IN KIND from every prior sibling's own domain-defining check. It is
  not about WHERE the equipment came from (contrast the metal-wholesale
  sibling's conflict-minerals chain-of-custody check) or WHO it is
  going to (contrast the general-trading sibling's export-license
  check, or the tech-wholesale sibling's denied-party-list check) -- it
  is about whether the SPECIFIC MACHINE has a valid, currently-effective
  PRODUCT-CERTIFICATION on file: an EPA/EU nonroad-engine emissions
  certificate (40 C.F.R. Part 1039 Tier 1-4 Final / Regulation (EU)
  2016/1628 Stage V) and a ROPS safety certificate (29 C.F.R. §1928.51 /
  the EU Machinery Directive's ROPS-equivalent essential requirement).
  This is a PRODUCT-COMPLIANCE gate, closer in kind to a manufacturing-
  certification check than a trade-control check.

  A SECOND structural novelty, distinct from every prior sibling's own
  type-gating shape: this vertical splits its product-certification
  concern into TWO checks gated on TWO INDEPENDENT boolean facts on the
  SAME equipment-order (`:engine-powered?`, `:ride-on?`), not a single
  commodity-type enum. Contrast:
    - the metal-wholesale sibling's `conflict-minerals-provenance-
      unverified-violations`: ONE check, gated on a SINGLE fact
      (`:metal-type` being a 3TG/cobalt metal).
    - the agri-raw-materials sibling's `phytosanitary-certificate-
      missing-violations` / `animal-health-certificate-missing-
      violations`: TWO checks, but gated on the TWO VALUES OF ONE
      MUTUALLY-EXCLUSIVE ENUM (`:consignment-kind` is EITHER `:plant`
      OR `:animal` -- never both, never neither).
    - THIS vertical: TWO checks, each gated on its OWN INDEPENDENT
      boolean (`:engine-powered?` and `:ride-on?`) that are NOT mutually
      exclusive and NOT derived from one enum -- a real machine can be
      engine-powered AND ride-on (a tractor), engine-powered and NOT
      ride-on (a stationary/portable engine-driven implement such as an
      irrigation pump unit), or NEITHER (a towed, non-self-propelled
      implement with no operator position of its own). `agmachtrade.
      store/demo-data`'s `eo-8` (towed implement, both facts false) and
      `eo-9` (stationary engine unit, `:engine-powered?` true BUT
      `:ride-on?` false) TOGETHER prove these two gates fire
      INDEPENDENTLY of each other, not merely as two faces of the same
      underlying commodity-type distinction -- see each check's own
      docstring below and `docs/adr/0001-architecture.md` Decision 4 for
      the full reasoning on why this is genuinely TWO checks rather than
      one folded 'certification-missing' rule.

  Seven checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / actuation), and the human may
  approve -- but see `agmachtrade.phase`: for `:stake :delivery/
  dispatch`/`:invoice/settle` (a real dispatch or invoice settlement) NO
  phase ever allows auto-commit either. Two independent layers agree
  that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source
                                       (`agmachtrade.facts`), or invent
                                       one?
    2. Evidence incomplete         -- for `:delivery/dispatch`/
                                       `:invoice/settle`, has the
                                       jurisdiction actually been
                                       verified with a full GENERIC
                                       counterparty-diligence evidence
                                       checklist on file? Deliberately
                                       does NOT include emissions/ROPS
                                       certification -- those are checks
                                       5/6 below.
    3. Credit uncleared            -- for `:delivery/dispatch`, the
                                       counterparty's credit has NOT been
                                       cleared (the leasing collateral-
                                       coverage discipline, applied to
                                       counterparty credit). Evaluated
                                       before dispatch.
    4. Contract missing            -- for `:delivery/dispatch`, no
                                       contract-terms are on file for the
                                       order. Evaluated before dispatch.
    5. Emissions certificate
       missing                       -- for `:delivery/dispatch`, WHEN
                                       `:engine-powered?` is true, no
                                       valid EPA/EU nonroad-engine
                                       emissions certificate
                                       (`:emissions-certificate?`) is on
                                       file. NO-OP for a non-engine
                                       towed implement -- there is
                                       nothing to certify against a Tier
                                       standard. THIS check has no analog
                                       in ANY prior sibling's governor:
                                       it is this vertical's own defining
                                       regulatory content.
    6. ROPS certification missing  -- for `:delivery/dispatch`, WHEN
                                       `:ride-on?` is true (an operator-
                                       ride machine where rollover is a
                                       real hazard), no valid ROPS
                                       certificate (`:rops-certified?`)
                                       is on file. NO-OP for a non-ride-
                                       on implement with no operator
                                       position -- there is no rollover
                                       hazard to certify against.
                                       GATED INDEPENDENTLY of check 5
                                       (see namespace docstring
                                       'CRITICAL STRUCTURAL DIFFERENCE'
                                       above): `eo-9` proves an engine-
                                       powered, non-ride-on machine fires
                                       check 5 but NOT this check.
    7. Counterparty sanctions flag
       unresolved                    -- for `:delivery/dispatch` and
                                       `:invoice/settle`, the counterparty
                                       has NOT passed OFAC / equivalent
                                       sanctions screening -- a HARD,
                                       un-overridable hold. Evaluated
                                       UNCONDITIONALLY at both actuation
                                       ops.
    8. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:delivery/dispatch`/
                                       `:invoice/settle` (REAL acts)
                                       -> escalate.

  Two more guards, double-dispatch/double-invoice prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-dispatched-violations`/
  `already-invoiced-violations` refuse to dispatch/invoice the SAME
  equipment-order twice, off dedicated `:dispatched?`/`:invoiced?` facts
  (never a `:status` value) -- the SAME 'check a dedicated boolean, not
  status' discipline every prior governor's guards establish, informed
  by `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320)."
  (:require [agmachtrade.facts :as facts]
            [agmachtrade.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Dispatching real physical farm machinery (tractors, combines,
  implements) to a counterparty from the wholesale yard/dealership and
  settling a real invoice (real money moving between counterparty and
  wholesaler) are the two real-world actuation events this actor
  performs -- a two-member set, matching every principal-trading
  sibling's own dual-actuation shape (this vertical has no analog of
  the tech-wholesale sibling's third `:technology/release` op -- farm
  machinery is always a physical good, never a deemed-export-style
  electronic release)."
  #{:delivery/dispatch :invoice/settle})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:certification/verify` (or `:delivery/dispatch`/`:invoice/settle`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's agricultural-machinery-trade / nonroad-engine-
  emissions / tractor-safety requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:certification/verify :delivery/dispatch :invoice/settle} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:delivery/dispatch`/`:invoice/settle`, the jurisdiction's
  required GENERIC counterparty-diligence evidence (credit-clearance
  record, contract/PO, sanctions-screening record) must actually be
  satisfied -- do not trust the advisor's self-reported confidence
  alone. Deliberately does NOT check emissions or ROPS certification --
  those are `emissions-certificate-missing-violations`/`rops-
  certification-missing-violations` below, each its own dedicated,
  independently type-gated check rather than a checklist item."
  [{:keys [op subject]} st]
  (when (contains? #{:delivery/dispatch :invoice/settle} op)
    (let [eo (store/equipment-order st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction eo) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(信用審査記録/契約書またはPO/制裁スクリーニング記録)が充足していない状態での提案"}]))))

(defn- credit-uncleared-violations
  "For `:delivery/dispatch`, refuses to dispatch physical farm machinery
  to a counterparty whose credit has NOT been cleared -- counterparty
  credit not cleared (the leasing collateral-coverage discipline,
  applied to counterparty credit). Evaluated at the yard, ahead of any
  physical handoff."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [eo (store/equipment-order st subject)]
      (when (not (true? (:credit-cleared? eo)))
        [{:rule :credit-uncleared
          :detail (str subject " の取引先信用審査(credit-clearance)が未了 -- 出荷提案は進められない")}]))))

(defn- contract-missing-violations
  "For `:delivery/dispatch`, refuses to dispatch physical farm machinery
  when no contract-terms are on file for the order."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [eo (store/equipment-order st subject)]
      (when (or (nil? (:contract-terms eo)) (= "" (:contract-terms eo)))
        [{:rule :contract-missing
          :detail (str subject " に契約条項(contract-terms)の記録が無い -- 出荷提案は進められない")}]))))

(defn- emissions-certificate-missing-violations
  "For `:delivery/dispatch`, WHEN `:engine-powered?` is true, refuses to
  dispatch a machine with no valid EPA/EU nonroad-engine emissions
  certificate (`:emissions-certificate?`) on file -- the Engine Family
  certificate under 40 C.F.R. Part 1039 (US Tier 1-4 Final) or the
  type-approval certificate under Regulation (EU) 2016/1628 (EU Stage
  V). THIS check has no analog in ANY prior sibling's governor: it is
  this vertical's own defining regulatory content, and is a genuine
  NO-OP for a non-engine towed implement (`:engine-powered?` false) --
  `agmachtrade.store/demo-data`'s `eo-8` (towed implement, no engine, no
  emissions-certificate on file) proves this directly: the order still
  dispatches cleanly, because a towed implement has no engine to
  certify against a Tier/Stage standard in the first place. See
  namespace docstring 'CRITICAL STRUCTURAL DIFFERENCE' for why this is
  gated on `:engine-powered?` alone, independently of
  `rops-certification-missing-violations` below."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [eo (store/equipment-order st subject)]
      (when (and (true? (:engine-powered? eo))
                 (not (true? (:emissions-certificate? eo))))
        [{:rule :emissions-certificate-missing
          :detail (str subject " (" (name (:equipment-type eo)) ") はエンジン搭載機だが"
                       "排出ガス認証(EPA Tier/EU Stage V型式証明)の記録が無い -- 出荷提案は進められない")}]))))

(defn- rops-certification-missing-violations
  "For `:delivery/dispatch`, WHEN `:ride-on?` is true (an operator-ride
  machine where rollover is a real hazard -- a tractor, combine, or
  self-propelled sprayer), refuses to dispatch a machine with no valid
  ROPS (Roll-Over Protective Structure) certificate (`:rops-certified?`)
  on file -- the safety certification 29 C.F.R. §1928.51 requires for
  agricultural tractors manufactured after October 25, 1976 (or the EU
  Machinery Directive's ROPS-equivalent essential requirement). THIS
  check has no analog in ANY prior sibling's governor: it is this
  vertical's own defining SAFETY (as opposed to emissions) regulatory
  content, and is a genuine NO-OP for a non-ride-on implement with no
  operator position (`:ride-on?` false) -- `agmachtrade.store/
  demo-data`'s `eo-8` (towed implement, no operator position, no ROPS
  certificate on file) proves this directly: the order still dispatches
  cleanly, because a towed implement has no rollover hazard to certify
  against in the first place.

  GATED INDEPENDENTLY of `emissions-certificate-missing-violations`
  above, NOT as a shared consequence of the same commodity-type fact --
  see namespace docstring 'CRITICAL STRUCTURAL DIFFERENCE': `eo-9`
  (a stationary/portable engine-driven implement -- `:engine-powered?`
  true, `:ride-on?` false, NEITHER certificate on file) proves this
  independence directly: the order HARD-holds on
  `emissions-certificate-missing` (it has an engine) but this check does
  NOT ALSO fire (it has no operator-ride position, so there is no
  rollover hazard to certify against), confirming the two checks are
  gated on genuinely separate real-world properties, not two symptoms of
  one underlying type."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [eo (store/equipment-order st subject)]
      (when (and (true? (:ride-on? eo))
                 (not (true? (:rops-certified? eo))))
        [{:rule :rops-certification-missing
          :detail (str subject " (" (name (:equipment-type eo)) ") は乗用/自走式機だが"
                       "ROPS(転倒時保護構造)安全認証の記録が無い -- 出荷提案は進められない")}]))))

(defn- counterparty-sanctions-flag-unresolved-violations
  "For `:delivery/dispatch` and `:invoice/settle`, an unresolved
  sanctions-screening flag -- the counterparty has NOT passed OFAC /
  equivalent sanctions screening -- is a HARD, un-overridable hold.
  Evaluated UNCONDITIONALLY at both actuation ops: neither machinery nor
  money moves against an unscreened counterparty."
  [{:keys [op subject]} st]
  (when (contains? #{:delivery/dispatch :invoice/settle} op)
    (let [eo (store/equipment-order st subject)]
      (when (not (true? (:sanctions-screened? eo)))
        [{:rule :counterparty-sanctions-flag-unresolved
          :detail (str subject " の取引先制裁スクリーニング(OFAC等)が未了 -- 出荷・請求提案は進められない")}]))))

(defn- already-dispatched-violations
  "For `:delivery/dispatch`, refuses to dispatch the SAME equipment-order
  twice, off a dedicated `:dispatched?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (when (store/equipment-order-already-dispatched? st subject)
      [{:rule :already-dispatched
        :detail (str subject " は既に出荷済み")}])))

(defn- already-invoiced-violations
  "For `:invoice/settle`, refuses to settle the SAME equipment-order's
  invoice twice, off a dedicated `:invoiced?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :invoice/settle)
    (when (store/equipment-order-already-invoiced? st subject)
      [{:rule :already-invoiced
        :detail (str subject " は既に請求済み")}])))

(defn check
  "Censors an AgMachTradeAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (credit-uncleared-violations request st)
                           (contract-missing-violations request st)
                           (emissions-certificate-missing-violations request st)
                           (rops-certification-missing-violations request st)
                           (counterparty-sanctions-flag-unresolved-violations request st)
                           (already-dispatched-violations request st)
                           (already-invoiced-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})

# ADR-0001: AgMachTradeAdvisor ⊣ Ag Equipment Governor architecture

## Status

Accepted. `cloud-itonami-isic-4653` published directly at `:implemented`
in the `kotoba-lang/industry` registry (no prior `:blueprint`-tier-only
scaffold stage for this repo).

## Context

`cloud-itonami-isic-4653` publishes an OSS business blueprint for
wholesale of agricultural machinery, equipment and supplies
(equipment-order intake, per-jurisdiction contract / sanctions
regulatory verification, product-certification compliance verification,
physical equipment dispatch, and invoice settlement). Like every prior
actor in this fleet, the blueprint alone is not an implementation: this
ADR records the governed-actor architecture that establishes it as
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established by
`cloud-itonami-isic-6511` (life insurance) and applied across many
prior siblings, most closely the fuel-wholesale
(`cloud-itonami-isic-4671`), metal-wholesale
(`cloud-itonami-isic-4662`), agri-raw-materials-wholesale
(`cloud-itonami-isic-4620`) and computer/tech-wholesale
(`cloud-itonami-isic-4651`) siblings.

Like the fuel-wholesale sibling and `cloud-itonami-isic-0162`
(community agronomy), this vertical has NO bespoke domain capability
library in `kotoba-lang` to wrap (verified: no
`kotoba-lang/agmachtrade`-style repo exists). This build therefore uses
self-contained domain logic -- the same pattern the majority of this
fleet's actors use, and the explicit differentiator from
`cloud-itonami-isic-4920` (which wraps a pre-existing
`kotoba-lang/logistics` library). The ag-machinery-trading checks
(credit-clearance, contract-on-file, emissions certification, ROPS
certification, sanctions-screening) are direct entity boolean reads in
`agmachtrade.governor`, off dedicated `:credit-cleared?` /
`:contract-terms` / `:emissions-certificate?` / `:rops-certified?` /
`:sanctions-screened?` facts on the `equipment-order` record -- NO pure
range-check functions are needed (contrast the crude sibling, whose
registry hosts its reservoir/annular/water-cut/H2S range checks).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:ag-equipment-governor`, is grep-verified UNIQUE fleet-wide -- no
naming-collision precedent question, a fresh independent build.

## Decision

### Decision 1: fresh governor identity, no reuse precedent needed

`:ag-equipment-governor` is grep-verified unique across every
`blueprint.edn` in this fleet. This build follows the SAME
governed-actor architecture as every prior actor, but with its own
distinct governor identity.

### Decision 2: self-contained domain logic, direct entity booleans (no `kotoba-lang/agmachtrade` to wrap, and no range-check functions to host)

Unlike `cloud-itonami-isic-4920` (freight, which delegates tracking-
number validation to a real, pre-existing `kotoba-lang/logistics`
capability library), and unlike the crude-extraction sibling (which
hosts pure physical range-check functions in its registry because its
governor re-verifies measured physical values), this ag-machinery-
wholesale vertical needs NEITHER: there is no pre-existing
ag-machinery-trading capability library to delegate to, AND the
governor's domain checks (credit-clearance, contract-on-file, emissions
certification, ROPS certification, sanctions-screening) are direct
entity boolean reads off the `equipment-order` record's own dedicated
facts -- not measured-value-vs-limit range comparisons. So
`agmachtrade.registry` is RECORD CONSTRUCTION ONLY (no range-check
functions), and `agmachtrade.governor` reads the order's booleans
directly.

### Decision 3: dual-actuation shape, SEQUENTIAL on the SAME `equipment-order` entity

Like the fuel-wholesale sibling's own `fuel-order` entity, this
vertical's `dispatch` and `settle` actuation events apply SEQUENTIALLY
to the SAME `equipment-order` -- physical dispatch happens first
(machinery leaves the wholesale yard/dealership), invoice settlement
happens later (the money side of the trade, custody / financial
transfer), on the same order record. This matches the sequential shape
every principal-trading sibling in this fleet uses (fuel, metal,
agri-raw-materials), unlike the retail sibling's `:kind`-distinguished
alternative-action shape and unlike the tech-wholesale sibling's own
THREE-member actuation set (which needed a third `:technology/release`
op for the deemed-export doctrine -- this vertical has no analog: farm
machinery is always a physical good, never a deemed-export-style
electronic release). `high-stakes` is `#{:delivery/dispatch
:invoice/settle}`; neither ever auto-commits at any phase.

### Decision 4: emissions certification and ROPS certification are TWO checks, gated on TWO INDEPENDENT booleans -- not one folded rule, and not a mutually-exclusive-enum split

This is the vertical's own architecturally novel decision, distinct
from every prior sibling's own type-gating shape:

- The metal-wholesale sibling's `conflict-minerals-provenance-
  unverified-violations` is ONE check gated on a SINGLE fact
  (`:metal-type` being a 3TG/cobalt metal), and internally folds TWO
  sub-facts (chain-of-custody documentation, conflict-free-smelter
  certification) into that ONE rule -- correct for that vertical
  because both sub-facts are evaluated on the SAME gating condition, so
  folding loses no independence information a reader would need.
- The agri-raw-materials sibling's `phytosanitary-certificate-missing-
  violations` / `animal-health-certificate-missing-violations` are TWO
  checks, but both branches derive from the TWO VALUES of ONE
  MUTUALLY-EXCLUSIVE enum, `:consignment-kind` (`:plant` XOR `:animal`
  -- an order is always exactly one, never both, never neither).
- THIS vertical's `emissions-certificate-missing-violations` /
  `rops-certification-missing-violations` are TWO checks, each gated on
  its OWN INDEPENDENT boolean (`:engine-powered?`, `:ride-on?`) that
  are NEITHER mutually exclusive NOR derived from one enum. A real
  machine can be (engine-powered, ride-on), (engine-powered, NOT
  ride-on -- e.g. a stationary/portable engine-driven implement), or
  (NOT engine-powered, NOT ride-on -- a towed implement). The fourth
  combination (ride-on but NOT engine-powered) is not populated in this
  R0's demo data because no realistic modern agricultural-machinery
  example was identified with confidence, but the governor's own check
  logic imposes no structural assumption that would make it impossible
  -- both checks read their own independent boolean and neither reads
  the other.

Why keep these as two checks rather than folding them the way the
metal-wholesale sibling folds its own two sub-facts? Because unlike
that sibling's sub-facts (evaluated on the SAME gating condition),
emissions certification and ROPS certification are governed by
GENUINELY DIFFERENT real-world regulatory regimes with DIFFERENT
triggering properties: emissions certification concerns air quality,
administered (in the US) by EPA under 40 C.F.R. Part 1039, triggered by
whether the machine carries an engine at all; ROPS certification
concerns operator physical safety, administered by OSHA under 29
C.F.R. §1928.51, triggered by whether the machine is an operator-ride
type where rollover is a hazard. Folding them into one
'certification-missing' rule would make the audit ledger ambiguous
about WHICH regime failed -- an air-quality gap and a life-safety gap
are not interchangeable events for a regulator, insurer, or plaintiff's
counsel reviewing this actor's history after an incident -- and would
hide that a machine can fail one check independently of the other. See
`docs/business-model.md` "Two genuinely independent type gates" for the
full reasoning, and `test/agmachtrade/governor_contract_test.clj`'s
`emissions-certificate-missing-is-held-and-unoverridable` (`eo-6`),
`rops-certification-missing-is-a-genuinely-different-failure-mode-from-
emissions` (`eo-7`),
`towed-implement-is-a-no-op-for-both-certification-checks` (`eo-8`), and
`stationary-engine-unit-proves-independent-type-gating` (`eo-9`) for the
end-to-end proof, especially `eo-9`: an engine-powered, non-ride-on
machine HARD-holds on emissions-certificate-missing but NOT on
rops-certification-missing, proving the two checks are gated on
genuinely separate properties rather than two symptoms of one
underlying commodity-type distinction (which `eo-8` alone could not
rule out).

### Decision 5: `counterparty-sanctions-flag-unresolved?` -- the open-flag-unresolved discipline

An unresolved sanctions-screening flag -- the counterparty has not
passed OFAC / equivalent sanctions screening -- is a HARD,
un-overridable hold. This reuses the SAME open-flag-unresolved
discipline the fuel-wholesale sibling's `counterparty-sanctions-flag-
unresolved-violations` check (and the freight sibling's
`delivery-exception-unresolved?` check) establish -- an open concern
cannot be silently suppressed to force a dispatch or invoice through.
Evaluated UNCONDITIONALLY at both `:delivery/dispatch` and
`:invoice/settle`.

### Decision 6: dedicated double-actuation-guard booleans

`:dispatched?` / `:invoiced?` are dedicated booleans on the
`equipment-order` record, never a single `:status` value -- the same
discipline every prior governor's guards establish, informed by
`cloud-itonami-isic-6492`'s real status-lifecycle bug
(ADR-2607071320).

### Decision 7: Store protocol, MemStore + DatomicStore parity

`agmachtrade.store/Store` is implemented by both `MemStore` (atom-
backed, default for dev/tests/demo) and `DatomicStore`
(`langchain.db`-backed), proven to satisfy the same contract in
`test/agmachtrade/store_contract_test.clj`. The ledger stays append-only
on every backend: which equipment-order was verified for a jurisdiction
with no official spec-basis, which counterparty had credit-uncleared /
no contract / a missing emissions certificate / a missing ROPS
certificate / an unresolved sanctions-screening flag, which order was
dispatched, which invoice was settled, on what jurisdictional basis,
approved by whom -- always a query over an immutable log.

### Decision 8: Phase 0->3 with `:delivery/dispatch`/`:invoice/settle` NEVER auto

`agmachtrade.phase`'s phase table puts `:order/intake` (no direct
capital risk) in phase 3's `:auto` set as its only member;
`:delivery/dispatch` and `:invoice/settle` are deliberately ABSENT from
every phase's `:auto` set, including phase 3 -- a permanent structural
fact. `agmachtrade.governor`'s high-stakes gate enforces the same
invariant independently: two layers agree that actuation is always a
human trading supervisor's call.

### Decision 9: mock + LLM advisor pair

`agmachtrade.agmachtradeadvisor` provides a deterministic `mock-advisor`
(default, runs offline) and an `llm-advisor` backed by a
`langchain.model/ChatModel`. The LLM advisor's EDN proposal is parsed
defensively: any parse/shape failure yields a safe low-confidence noop
so the governor escalates/holds -- an LLM hiccup can never auto-dispatch
equipment or auto-settle an invoice.

### Decision 10: `:robotics false` -- the dispatch entity is human-operated, not robot-handled

Unlike the fuel-wholesale sibling's loading-rack valve robot or the
computer-peripheral sibling's AS/RS carton-picking shuttle, this
vertical's `:delivery/dispatch` op gates the equipment-order itself --
a self-propelled machine driven off the lot by a human operator, or a
towed implement hitched and towed by a human-driven tow vehicle. No
comparable fixed, robot-actuatable apparatus exists in general
commercial practice today for moving this entity. `blueprint.edn`
therefore sets `:itonami.blueprint/robotics false`, and
`:required-technologies` drops `:robotics` entirely -- following the
general-trading sibling's own precedent of a `robotics false` blueprint
when there is no apparatus the governor could plausibly gate. See
`docs/business-model.md` Robotics Premise for the full reasoning,
including the deliberately-rejected tempting counterpoint (automated
parts-inventory retrieval is real but gates a DIFFERENT entity than
this actor's own `:delivery/dispatch`).

## Alternatives considered

- **Wrapping a bespoke `kotoba-lang/agmachtrade` capability library.**
  Considered and explicitly ruled out: no such library exists. Forcing
  a false capability-library integration would be dishonest; this
  build correctly uses self-contained domain logic instead.
- **Folding emissions-certificate-missing and rops-certification-
  missing into ONE `certification-missing` check.** Considered and
  ruled out (see Decision 4): the two checks are gated on genuinely
  independent properties governed by different regulatory regimes with
  different real-world consequences; folding them would erase that
  distinction from the audit ledger and from the type-gating proof
  itself.
- **Gating both certification checks on a single `:equipment-type`
  enum** (mirroring the agri-raw-materials sibling's `:consignment-kind`
  split). Considered and ruled out: unlike plant/animal consignments (a
  true mutually-exclusive either/or), engine-powered-ness and ride-on-
  ness are independent real-world properties that can combine in more
  than two ways -- an enum would either lose information (if coarse) or
  require enumerating every real combination as its own case (more
  brittle than two independent booleans).
- **A `:kind`-distinguished entity** (matching the retail sibling's
  `order` shape). Rejected: dispatch and invoice settlement happen
  SEQUENTIALLY on the SAME equipment-order in this domain, not as
  alternative actions -- the fuel-wholesale / metal-wholesale /
  agri-raw-materials cluster's sequential shape is the honest match
  here.
- **`:robotics true` on the strength of automated parts-inventory
  retrieval.** Considered and ruled out (see Decision 10): parts
  retrieval is a real robotics use case at large ag-equipment
  distributors, but it gates a DIFFERENT entity (a parts-order) than
  this actor's own governed `equipment-order` dispatch -- claiming
  `:robotics true` on its strength would misrepresent what this
  actor's own governor actually gates.
- **Building fulfillment routing / trading-book optimization in this
  R0.** Rejected in favor of a scoped R0 slice (the `:optimization`
  capability is correctly marked required, the integration is a
  follow-up), consistent with this fleet's 'extending coverage is
  additive' convention.

## Consequences

- Fresh independent actor in this fleet, following the SAME
  governed-actor architecture as every prior sibling.
- Establishes a NEW type-gating shape for this fleet: two checks gated
  on two independent, non-enum booleans, proven distinct from both the
  metal-wholesale sibling's single-fact-gated fold and the
  agri-raw-materials sibling's mutually-exclusive-enum split.
- `MemStore` || `DatomicStore` parity is proven by
  `test/agmachtrade/store_contract_test.clj`.
- 38 tests / 192 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks one clean dispatch + invoice lifecycle,
  the two type-gating control cases (`eo-8`, `eo-9`), plus six other
  HARD-hold scenarios, end-to-end.
- `blueprint.edn` sets `:robotics false` and omits `:robotics` from
  `:required-technologies`, an honest departure from the fuel-wholesale/
  metal-wholesale/computer-peripheral siblings' own `:robotics true`.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of the
  general governed-actor architecture pattern)
- `cloud-itonami-isic-4671/docs/adr/0001-architecture.md` (fuel-
  wholesale sibling; origin of the sequential dual-actuation shape and
  the self-contained-domain-logic pattern this build follows)
- `cloud-itonami-isic-4662/docs/adr/0001-architecture.md` (metal-
  wholesale sibling; contrast: single-fact-gated check with two sub-
  facts folded into one rule)
- `cloud-itonami-isic-4620/docs/adr/0001-architecture.md` (agri-raw-
  materials sibling; contrast: two checks split on a mutually-exclusive
  enum, not two independent booleans)
- `cloud-itonami-isic-4690/docs/business-model.md` (general-trading
  sibling; origin of the `robotics false` / no-apparatus-to-gate
  reasoning this build's Robotics Premise follows)
- 40 C.F.R. Part 1039 (Control of Emissions from New and In-Use
  Nonroad Compression-Ignition Engines -- US EPA, Tier 1-4 Final)
- 29 C.F.R. §1928.51 (Roll-Over Protective Structures -- US OSHA,
  agricultural tractors manufactured after October 25, 1976)
- Regulation (EU) 2016/1628 (Stage V nonroad mobile machinery emission
  standards -- EU)
- Machinery Directive 2006/42/EC (harmonized ROPS-equivalent safety
  essential requirements -- EU; successor Machinery Regulation (EU)
  2023/1230 application date not independently confirmed by this build)

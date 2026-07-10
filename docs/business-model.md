# Business Model: Wholesale of Agricultural Machinery, Equipment and Supplies

## Classification
- Repository: `cloud-itonami-isic-4653`
- ISIC Rev.5: `4653` -- wholesale of agricultural machinery, equipment
  and supplies
- Domain: `downstream/agricultural-machinery-wholesale`
- Social impact: equipment safety, air quality, transparency
- Governor: `:ag-equipment-governor`
- License: AGPL-3.0-or-later

## Scope
This actor covers equipment-order intake through per-jurisdiction
contract / sanctions regulatory verification, product-certification
compliance verification (emissions AND ROPS safety, each its own gate),
physical equipment dispatch (real tractors, combines, implements and
supplies leaving the wholesale yard/dealership for a counterparty), and
invoice settlement (the money side of a wholesale-equipment trade,
custody / financial transfer). It does **not**, by itself, hold any
wholesale dealership licence or operating authority required to run an
ag-machinery-wholesale business in a given jurisdiction, perform the
actual physical loading/transport of equipment, or judge trading-book
economics (fulfillment routing and trading-book optimization is a
follow-up slice, not this R0). Whoever deploys a live instance supplies
the jurisdiction-specific operating authority, the real transport/
logistics integration and ERP / accounts-receivable integrations, and
bears that jurisdiction's liability -- the software supplies the
governed, spec-cited, audited execution scaffold so the operator does
not have to build the compliance layer from scratch.

## Customer
- regional and independent agricultural-machinery dealerships and
  wholesale distributors
- OEM/multi-line equipment distributors leaving closed dealership-
  management / ERP SaaS
- farm cooperatives and equipment-leasing operators sourcing wholesale
- counterparties, banks and regulators (including EPA and OSHA) who
  need an auditable, spec-cited trade record

## Offer
- equipment-order intake and directory management
- per-jurisdiction contract / sanctions regulatory verification with an
  official spec-basis citation
- emissions-certification compliance verification for engine-powered
  machinery, and ROPS safety-certification compliance verification for
  ride-on machinery -- each its own independently type-gated check
- physical equipment dispatch gated on full evidence, a credit-cleared
  counterparty, contract-terms on file, and a passed sanctions screen
- invoice settlement (custody / financial transfer) with double-invoice
  prevention
- evidence checklisting (credit-clearance record, contract/PO,
  sanctions-screening record)
- sanctions and credit exception workflows
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per dealership / yard
- support retainer with SLA
- ERP and accounts-receivable integration

## The `:ag-equipment-governor` Decision Rule

This blueprint's `:itonami.blueprint/governor` is `:ag-equipment-
governor`. It is the single authority that stands between "physical
farm machinery could be dispatched to a counterparty" and "it is
allowed to leave the yard," and between "an invoice could be settled"
and "it is allowed to settle." Every rule it enforces is traceable to
the domain (Wholesale of Agricultural Machinery, Equipment and
Supplies, ISIC 4653) and to the three `:social-impact` tags in
`blueprint.edn` (`:equipment-safety`, `:air-quality`, `:transparency`).

This is the rule the companion contract test
(`test/agmachtrade/governor_contract_test.clj`) encodes end-to-end: the
AgMachTradeAdvisor never dispatches physical equipment to a counterparty
or settles an invoice the Ag Equipment Governor would reject,
`:delivery/dispatch` and `:invoice/settle` NEVER auto-commit at any
phase, `:order/intake` (no direct capital risk) MAY auto-commit when
clean, and every decision (commit OR hold) leaves exactly one ledger
fact.

**Authorizes a physical equipment dispatch (`:delivery/dispatch`) or
invoice settlement (`:invoice/settle`) only when ALL of the following
hold:**

1. **An official spec-basis citation exists for the jurisdiction** --
   the governor will not authorize any `:certification/verify`,
   `:delivery/dispatch`, or `:invoice/settle` proposal whose
   jurisdiction has no entry in the `agmachtrade.facts` catalog
   (`:no-spec-basis`). This is the direct enforcement of
   `:transparency`: a jurisdiction whose ag-machinery-wholesale /
   sanctions requirements cannot be traced to an OFFICIAL public source
   is never guessed. The advisor must not fabricate a jurisdiction's
   requirements.
2. **The jurisdiction's required GENERIC evidence is fully on file** --
   for a dispatch or invoice the order's jurisdiction must have been
   verified with a complete counterparty-diligence evidence checklist
   on record: the credit-clearance record, the contract / purchase
   order, and the sanctions-screening (OFAC / equivalent) record
   (`:evidence-incomplete`). This protects `:transparency`: an order
   that cannot prove counterparty diligence never dispatches.
3. **The counterparty's credit has been cleared** -- the governor reads
   the dedicated `:credit-cleared?` fact on the order and refuses to
   dispatch equipment when credit has NOT been cleared (the leasing
   collateral-coverage discipline, applied to counterparty credit)
   (`:credit-uncleared`). Evaluated at `:delivery/dispatch`.
4. **Contract-terms are on file** -- the governor refuses to dispatch
   when no `:contract-terms` are recorded for the order
   (`:contract-missing`). Physical equipment never leaves the yard
   against an undocumented trade. Evaluated at `:delivery/dispatch`.
5. **An engine-powered machine has a valid emissions certificate on
   file** -- when `:engine-powered?` is true, the governor refuses to
   dispatch unless `:emissions-certificate?` is true
   (`:emissions-certificate-missing`). This protects `:air-quality`: no
   EPA/EU nonroad-engine emissions certificate on file means the
   machine's Engine Family / type-approval status cannot be verified.
   NO-OP for a non-engine towed implement. Evaluated at `:delivery/
   dispatch`.
6. **A ride-on machine has a valid ROPS certificate on file** -- when
   `:ride-on?` is true (an operator-ride machine where rollover is a
   real hazard), the governor refuses to dispatch unless
   `:rops-certified?` is true (`:rops-certification-missing`). This
   protects `:equipment-safety`: no ROPS certificate on file means the
   rollover-protection status of a machine an operator will ride cannot
   be verified. NO-OP for a non-ride-on implement with no operator
   position. Evaluated at `:delivery/dispatch`, INDEPENDENTLY of check
   5 (see "Two genuinely independent type gates" below).
7. **The counterparty has passed OFAC / equivalent sanctions
   screening** -- the governor reads the dedicated
   `:sanctions-screened?` fact and treats an unresolved sanctions-
   screening flag as a HARD, un-overridable hold
   (`:counterparty-sanctions-flag-unresolved`). Neither equipment nor
   money moves against an unscreened counterparty. Evaluated
   UNCONDITIONALLY at both `:delivery/dispatch` and `:invoice/settle`.
8. **The order has not already been dispatched, and the invoice has not
   already been settled** -- a double dispatch of the same order is
   refused off a dedicated `:dispatched?` fact, and a double invoice off
   a dedicated `:invoiced?` fact (never a `:status` value), the
   double-actuation guard every sibling actor in this fleet enforces
   (`:already-dispatched` / `:already-invoiced`).

**Rejects (HOLD, un-overridable, never even reaches a human) when any of
the above fail.** A proposal with no spec-basis, incomplete evidence, an
uncleared counterparty credit, no contract-terms on file, a missing
emissions certificate, a missing ROPS certificate, an unresolved
sanctions-screening flag, or a double dispatch/invoice is held at the
governor node -- a human approver cannot override these, by
construction.

**Always escalates to a human (never auto-commits) for `:delivery/
dispatch` and `:invoice/settle`**, even when every check above is clean.
Dispatching real physical farm machinery to a counterparty at the
wholesale yard and settling a real invoice (real money moving between
counterparty and wholesaler) are the two real-world actuation events
this actor performs; both are always a human trading supervisor's call.
This is enforced by TWO independent layers that agree on purpose: the
governor's confidence / actuation SOFT gate (a `:delivery/dispatch` /
`:invoice/settle` stake always escalates) and `agmachtrade.phase`'s
phase table, which never puts either op in any phase's `:auto` set. The
`:equipment-safety`/`:air-quality` tags are enforced upstream of
generic evidence, in the certification-specific checks themselves --
the governor's job is dispatch/invoice authorization integrity, not
trading-book optimization.

## Two genuinely independent type gates: emissions vs. ROPS, and why they are TWO checks

Every prior wholesale-trading sibling in this fleet type-gates its own
domain-defining check on a SINGLE property: the metal-wholesale
sibling's `conflict-minerals-provenance-unverified` fires when
`:metal-type` is a 3TG metal or cobalt (one boolean predicate over one
enum); the agri-raw-materials sibling splits its check in two
(`phytosanitary-certificate-missing` / `animal-health-certificate-
missing`), but both branches come from the TWO VALUES of ONE MUTUALLY-
EXCLUSIVE enum, `:consignment-kind` (an order is EITHER `:plant` OR
`:animal`, never both, never neither).

This vertical is different again: it also splits into two checks, but
each is gated on its OWN INDEPENDENT boolean fact
(`:engine-powered?`, `:ride-on?`) that are NOT mutually exclusive and
NOT two values of one enum. A real piece of agricultural machinery can
be:
- engine-powered AND ride-on (a tractor, combine, or self-propelled
  sprayer) -- BOTH certification gates apply.
- engine-powered but NOT ride-on (a stationary or portable engine-
  driven implement, such as an irrigation pump unit or an engine-driven
  grain auger) -- ONLY the emissions gate applies; there is no operator
  position, so no rollover hazard to certify against.
- NEITHER engine-powered NOR ride-on (a towed implement -- a plow, disc
  harrow, planter, or trailer, hitched to and pulled by a separate
  tractor) -- NEITHER gate applies.

Why does this matter enough to be two checks rather than one folded
'certification-missing' rule (the way the metal-wholesale sibling folds
its own two sub-facts, chain-of-custody AND conflict-free-smelter
certification, into ONE rule)? Because emissions certification and ROPS
certification are governed by GENUINELY DIFFERENT real-world regimes --
different agencies (in the US, EPA for emissions vs. OSHA for tractor
safety), different statutes (40 C.F.R. Part 1039 vs. 29 C.F.R.
§1928.51), different underlying concerns (air quality vs. operator
physical safety), and -- most importantly for the type-gating question
-- different TRIGGERING PROPERTIES of the machine itself. Folding them
into one rule would make the audit ledger ambiguous about WHICH regime
actually failed (an air-quality gap and a life-safety gap are not
interchangeable events for a regulator or an insurer reviewing this
actor's history), and would obscure that a machine can fail one and not
the other independently -- exactly the case the metal-wholesale
sibling's own two sub-facts do NOT have (chain-of-custody and
conflict-free-smelter certification are both evaluated on the SAME
gating condition, `:metal-type`, so folding them loses no independence
information; here, folding would).

`agmachtrade.governor` therefore keeps `emissions-certificate-missing-
violations` and `rops-certification-missing-violations` as TWO SEPARATE
HARD checks, proven genuinely independent end-to-end by
`test/agmachtrade/governor_contract_test.clj`:

- `emissions-certificate-missing-is-held-and-unoverridable` (`eo-6`) --
  an engine-powered, ride-on tractor missing its emissions certificate
  HOLDS on `:emissions-certificate-missing` and explicitly asserts
  `:rops-certification-missing` did NOT ALSO fire (its ROPS certificate
  IS on file).
- `rops-certification-missing-is-a-genuinely-different-failure-mode-
  from-emissions` (`eo-7`) -- the mirror case: a ride-on tractor missing
  its ROPS certificate HOLDS on `:rops-certification-missing` and
  explicitly asserts `:emissions-certificate-missing` did NOT ALSO fire
  (its emissions certificate IS on file).
- `towed-implement-is-a-no-op-for-both-certification-checks` (`eo-8`) --
  a towed implement with `:engine-powered?` false, `:ride-on?` false,
  and NEITHER certificate on file dispatches CLEANLY. This proves both
  checks are TRUE NO-OPs for a non-engine, non-ride-on implement, not a
  blanket certificate requirement silently waived.
- `stationary-engine-unit-proves-independent-type-gating` (`eo-9`) --
  THE decisive proof of independence: a stationary/portable
  engine-driven implement (`:engine-powered?` true, `:ride-on?` false,
  NEITHER certificate on file) HARD-holds on `:emissions-certificate-
  missing` (it has an engine, so the emissions gate applies) but
  `:rops-certification-missing` does NOT ALSO fire (it has no operator-
  ride position, so there is no rollover hazard to certify against,
  EVEN THOUGH its own `:rops-certified?` fact reads false, same as
  `:emissions-certificate?`). `eo-8` alone could not prove this -- a
  reader could otherwise suspect the two checks are secretly the same
  underlying 'is this a towed implement?' test; `eo-9` demonstrates the
  two gates evaluate genuinely SEPARATE real-world properties of the
  SAME machine.

## Required Technologies

`blueprint.edn`'s `:itonami.blueprint/required-technologies` for this
business, and what each one is actually load-bearing for here (not a
generic capability list):

| Technology | What it is FOR in Wholesale of Agricultural Machinery, Equipment and Supplies |
|---|---|
| `:identity` | Trader, trading-supervisor, yard-operator and counterparty identity plus role-based access, so the governor's sign-off is tied to *who* authorized a dispatch or invoice, not just *that* someone did. |
| `:forms` | Structured intake for equipment-order booking, per-jurisdiction evidence capture (credit-clearance record, contract/PO, sanctions-screening record), certification-record capture (emissions certificate, ROPS certificate), and sanctions / credit exception submission -- the data the Decision Rule above actually evaluates comes in through these forms. |
| `:dmn` | Encodes the `:ag-equipment-governor` Decision Rule itself (spec-basis, evidence completeness, credit-clearance, contract-on-file, emissions-certificate-missing, rops-certification-missing, sanctions-screening, the double-actuation guards, the actuation gate) as an evaluable decision table rather than code buried in application logic -- this is what makes the governor auditable and swappable per-deployment. |
| `:bpmn` | Orchestrates the intake -> verify -> dispatch -> settle -> audit loop end-to-end (see `docs/operator-guide.md`) across equipment-order intake, certification verification, physical dispatch, and invoice settlement, including the certification / sanctions / credit escalation gates. |
| `:audit-ledger` | The immutable record of every verification, dispatch, invoice, certification flag, sanctions flag, and hold -- this is what "an auditable, spec-cited trade record for every dispatch and invoice" (Trust Controls, below) actually means in practice, and the evidence an operator needs if a dispatch or an invoice is later disputed by a counterparty or regulator (including EPA or OSHA). |
| `:optimization` | Fulfillment routing and trading-book optimization -- selects the profitable fulfillment strategy for a wholesale order book. This R0 build deliberately scopes optimization OUT (see README `Business-process coverage`); the capability is correctly marked required, the integration is a follow-up slice. |

There is NO bespoke `:agmachtrade` capability library in this stack
(unlike the freight sibling's own `:logistics`): the ag-machinery-
trading checks (credit-clearance, contract-on-file, emissions
certification, ROPS certification, sanctions-screening) are direct
entity boolean reads in `agmachtrade.governor`, on top of the generic
identity/forms/dmn/bpmn/audit-ledger stack (see Capability layer and
Robotics Premise, below).

## Trust Controls
- a jurisdiction with no official spec-basis can never be verified,
  dispatched, or invoiced against
- a dispatch never starts with incomplete counterparty-diligence
  evidence
- a dispatch never starts with an uncleared counterparty credit, no
  contract-terms on file, a missing emissions certificate for an
  engine-powered machine, a missing ROPS certificate for a ride-on
  machine, or an unresolved sanctions-screening flag
- an invoice never settles against an unresolved sanctions-screening
  flag
- certification / sanctions / credit flags cannot be silently
  suppressed
- the same order can never be dispatched or invoiced twice
- a dispatch or invoice never auto-commits; both always need a human
  trading supervisor
- every dispatch and invoice (commit OR hold) leaves exactly one
  immutable ledger fact
- counterparty, credit, certification and trade data stays outside Git

## Implementation notes (`:implemented`)

The Decision Rule above is implemented faithfully by
`agmachtrade.governor` as six HARD checks (a human approver cannot
override them) plus one unconditional HARD check plus one SOFT gate:

- `spec-basis-violations` -- the spec-basis check above, evaluated on
  every `:certification/verify`, `:delivery/dispatch`, and
  `:invoice/settle`.
- `evidence-incomplete-violations` -- the GENERIC evidence-completeness
  check above, for `:delivery/dispatch` / `:invoice/settle`.
  Deliberately does NOT check emissions or ROPS certification.
- `credit-uncleared-violations` -- the counterparty-credit check above
  (the leasing collateral-coverage discipline applied to counterparty
  credit); evaluated on every `:delivery/dispatch`.
- `contract-missing-violations` -- the contract-on-file check above;
  evaluated on every `:delivery/dispatch`.
- `emissions-certificate-missing-violations` -- gated on
  `:engine-powered?`; evaluated on every `:delivery/dispatch`.
- `rops-certification-missing-violations` -- gated on `:ride-on?`,
  INDEPENDENTLY of the emissions check; evaluated on every
  `:delivery/dispatch`.
- `counterparty-sanctions-flag-unresolved-violations` -- the sanctions-
  screening check above; evaluated unconditionally on both
  `:delivery/dispatch` and `:invoice/settle`.
- `already-dispatched-violations` / `already-invoiced-violations` -- the
  double-actuation guards above, off dedicated `:dispatched?` /
  `:invoiced?` booleans (never a `:status` value), the same discipline
  every sibling governor's guards establish.
- the confidence floor / actuation SOFT gate -- low confidence, OR a
  `:delivery/dispatch` / `:invoice/settle` stake, escalates to a human;
  and `agmachtrade.phase` independently never auto-commits either op at
  any phase.

Unlike the crude-extraction sibling's governor (which calls pure
physical range-check functions in its registry), this governor needs no
range-check functions at all: its domain checks read the
`equipment-order` record's own dedicated booleans directly.
`:delivery/dispatch` and `:invoice/settle` are the two real-world
actuation events (`#{:delivery/dispatch :invoice/settle}`), applied
SEQUENTIALLY to the SAME equipment-order (dispatch first, invoice
settlement later) rather than the retail sibling's `:kind`-distinguished
alternative-action shape -- the same sequential dual-actuation shape the
fuel-wholesale, metal-wholesale and agri-raw-materials siblings use.
Neither ever auto-commits at any phase. Fulfillment routing and
trading-book optimization (the `:optimization` line above) is a
follow-up slice, not in this R0 build -- see README `Business-process
coverage`.

## Capability layer

Unlike `cloud-itonami-isic-4920` (which wraps a pre-existing bespoke
capability library `kotoba-lang/logistics`), this vertical is
SELF-CONTAINED: there is no `kotoba-lang/agmachtrade` to delegate
ag-machinery-trading validation to. The credit-clearance /
contract-on-file / emissions-certificate / ROPS-certificate /
sanctions-screening checks live as direct entity boolean reads in
`agmachtrade.governor` (off dedicated `:credit-cleared?` /
`:contract-terms` / `:emissions-certificate?` / `:rops-certified?` /
`:sanctions-screened?` facts on the `equipment-order` record) -- this
vertical's governor needs no pure range-check functions at all
(contrast the crude sibling, whose registry hosts its physical range
checks), because its domain checks ARE direct boolean reads.

## Jurisdiction coverage (honest)

`agmachtrade.facts/catalog` currently seeds 2 jurisdictions with an
official spec-basis, each citing the SPECIFIC emissions AND safety
regulatory framework (not merely a generic customs statute):

- **USA**: the U.S. Environmental Protection Agency (EPA) administering
  40 C.F.R. Part 1039 (nonroad compression-ignition engine emission
  standards, the Tier 1 through Tier 4 Final progression, Engine
  Family certification), and the Occupational Safety and Health
  Administration (OSHA) administering 29 C.F.R. §1928.51 (Roll-Over
  Protective Structures for agricultural tractors manufactured after
  October 25, 1976, referencing the ASAE/SAE S519 design/test
  standard), plus OFAC sanctions programs.
- **DEU** (the EU-representative jurisdiction; Regulation (EU)
  2016/1628 is directly applicable in every EU member state): the
  Kraftfahrt-Bundesamt (KBA) within the EU type-approval framework
  administering Regulation (EU) 2016/1628 (the EU 'Stage V' nonroad
  mobile machinery emission standard, the real EU analog of the US Tier
  system), and Machinery Directive 2006/42/EC (harmonized ROPS-
  equivalent essential safety requirements, satisfied via EN ISO 3471),
  plus EU financial sanctions regulations.

This is a starting catalog to prove the governor contract end-to-end,
not a claim of global coverage (2 of ~194 jurisdictions worldwide).
Adding a jurisdiction is additive: one map entry in
`agmachtrade.facts/catalog`, citing a real official source -- never
fabricate a jurisdiction's requirements to make coverage look bigger.

**Honest uncertainty flag for independent verification**: this build is
confident about 40 C.F.R. Part 1039's Tier 1-4 Final structure and
Engine Family certification mechanic, about 29 C.F.R. §1928.51's
October 25, 1976 manufacture-date threshold, and about Regulation (EU)
2016/1628's Stage V emission-standard role (all from training-
knowledge, no live web access -- the same "no live web access, cite
only what is confident from training knowledge" discipline every
sibling's own jurisdiction catalog follows). This build is LESS
confident about, and flags for independent verification:

- the EXACT application date of the EU's Machinery Regulation (EU)
  2023/1230 relative to the CURRENT Machinery Directive 2006/42/EC's
  repeal -- this catalog cites 2006/42/EC as the presently-operative
  safety framework and names 2023/1230 as the adopted-but-not-yet-
  confirmed-applicable successor; an operator should confirm which
  instrument is actually in force on the date of use.
- the PRECISE current OECD Standard Code number and narrow-track/
  wide-track scope for internationally-referenced ROPS testing (this
  build names "OECD Code 4" and ISO 3471 for context in
  `agmachtrade.facts`'s namespace docstring, but does not rely on the
  exact code number as an independent jurisdiction entry -- only the
  two seeded jurisdictions' own statutory citations above are asserted
  with full confidence).
- whether 40 C.F.R. Part 1039 vs. a companion regulation (e.g. Part
  1054 for smaller nonroad spark-ignition engines) is the precise
  applicable citation for every engine size/fuel-type combination a
  real ag-machinery wholesaler might carry -- this R0 models
  `:engine-powered?` as a single boolean and does not yet split by
  engine size/fuel-type the way a future extension could (see README
  `Business-process coverage` for the "extending coverage is additive"
  discipline this would follow).

## Maturity

`:implemented` -- `AgMachTradeAdvisor` + `Ag Equipment Governor` run as
real, tested code (`clojure -M:dev:test`: 38 tests / 192 assertions, 0
failures; lint clean), following the SAME governed-actor architecture
as the other prior actors across this fleet, with its own distinct,
independently-named governor and its own direct-entity-boolean
ag-machinery-trading checks. See `docs/adr/0001-architecture.md` for
the design.

## Robotics Premise

`blueprint.edn` sets `:itonami.blueprint/robotics false`, and
`:itonami.blueprint/required-technologies` does NOT list `:robotics` at
all -- a deliberate, honest departure from the fuel-wholesale sibling's
`robotics true` (an autonomous loading-rack valve robot) and the
computer-peripheral sibling's `robotics true` (an automated storage-
and-retrieval system for palletized cartons).

**The entity this actor's `:delivery/dispatch` op actually gates is the
equipment-order itself** -- a tractor, combine, self-propelled sprayer,
or towed implement. In real commercial practice, physical dispatch of
THIS entity is a HUMAN-operated act, not a robotic-handling act:

- A self-propelled machine (tractor, combine, self-propelled sprayer)
  is DRIVEN off the wholesale lot -- either under its own power (onto
  public roads or a staging area) or onto a transport trailer -- by a
  human operator physically in the seat. This is structurally closer to
  a vehicle-dealership dispatch mechanic (a human driving the vehicle)
  than to a bulk-commodity loading-rack or palletized-carton handling
  mechanic.
- A non-self-propelled towed implement (a plow, disc harrow, planter,
  or trailer) is hitched to, and towed by, a separate human-driven tow
  vehicle, or repositioned within the yard by a human-operated forklift
  or yard hostler.

Unlike the general-trading sibling's total absence of a fixed physical
site (its own `:shipment/dispatch` is a pure logistics-coordination
referral, with no apparatus of any kind to gate a robot command
against), an ag-machinery wholesale yard/dealership DOES have a fixed
physical site. But having a fixed site is not, by itself, sufficient
for an honest `:robotics true` claim -- the fuel-wholesale and
computer-peripheral siblings' own claims are grounded in a SPECIFIC
apparatus (a loading-rack valve; an AS/RS shuttle) that can physically
perform THEIR dispatch act. No comparable apparatus exists in general
commercial practice today for moving a multi-tonne self-propelled
vehicle, or a towed implement, off a dealership yard -- the realistic
mechanism remains, and is expected to remain, a human operator in the
seat or on the tow vehicle.

**A tempting-but-out-of-scope counterpoint, addressed directly:** large
ag-equipment dealer groups and OEM distribution networks commonly DO
run automated storage-and-retrieval systems for the REPLACEMENT-PARTS
side of the business (filters, belts, hydraulic fittings, small
components) -- a genuine, real-world robotics claim, directly analogous
to the computer-peripheral sibling's own AS/RS citation. This build does
NOT claim `:robotics true` on the strength of that fact, because parts-
inventory retrieval is a DIFFERENT entity/order than the
`equipment-order` this actor's governed `:delivery/dispatch` op actually
gates (this R0 build is scoped to the machinery itself, not a separate
parts-order entity -- see README `Business-process coverage`). Claiming
`:robotics true` on the strength of a capability this actor's own
governed op does not gate would repeat the same category of dishonesty
the fleet's "never claim what you don't gate" discipline forbids for
every other vertical. A future extension modeling a dedicated parts-
order entity with its own dispatch op could reasonably revisit this
call for THAT op specifically, following the tech-wholesale sibling's
own precedent of a MIXED, path-specific robotics claim (true for one
actuation path, false for another) rather than a single blanket value.

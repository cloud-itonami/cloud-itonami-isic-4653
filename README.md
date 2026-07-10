# cloud-itonami-isic-4653

Open Business Blueprint for **ISIC Rev.5 4653**: Wholesale of
Agricultural Machinery, Equipment and Supplies -- equipment-order
intake, per-jurisdiction counterparty-diligence / sanctions regulatory
verification, emissions-certification AND ROPS-safety-certification
compliance verification, physical equipment dispatch, and invoice
settlement for a wholesale trader of durable agricultural equipment
(tractors, combines, implements, and supplies).

This repository publishes an agricultural-machinery-wholesale actor --
equipment-order intake, per-jurisdiction contract / sanctions
regulatory verification, product-certification compliance verification,
physical dispatch and invoice settlement -- as an OSS business that any
qualified operator can fork, deploy, run, improve and sell, so a
regional ag-equipment dealer never surrenders counterparty, credit,
certification and trade data to a closed dealership-management / ERP
SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **AgMachTradeAdvisor ⊣
Ag Equipment Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:ag-equipment-governor`, is a
UNIQUE keyword fleet-wide (grep-verified: no other blueprint declares
it) -- a fresh, independent build.

**This vertical is SELF-CONTAINED**: there is no
`kotoba-lang/agmachtrade` to delegate certification validation to, so
the credit-clearance / contract-on-file / emissions-certification /
ROPS-certification / sanctions-screening checks live as direct entity
boolean reads in `agmachtrade.governor` (off dedicated
`:credit-cleared?` / `:contract-terms` / `:emissions-certificate?` /
`:rops-certified?` / `:sanctions-screened?` facts on the
`equipment-order` record), rather than wrapping an external capability
library's own validated function.

> **Why an actor layer at all?** An LLM is great at drafting an order
> summary, normalizing records, and reading a diligence file -- but it
> has **no notion of which jurisdiction's agricultural-machinery-
> wholesale / sanctions law is official, no license to dispatch real
> physical farm machinery to a counterparty or settle a real invoice,
> and no way to know on its own whether the counterparty's credit has
> actually been cleared, whether contract terms are actually on file,
> whether THIS specific machine actually has a valid EPA/EU nonroad-
> engine emissions certificate on file, whether THIS specific machine
> actually has a valid ROPS safety certificate on file, or whether OFAC
> / equivalent sanctions screening has actually been passed**. Letting
> it dispatch equipment or settle an invoice directly invites
> fabricated regulatory citations, uncertified farm machinery leaving
> the yard for a working farm, and an invoice settling against a
> sanctioned party -- exposing the operator to real enforcement and
> financial liability, for whoever runs it. This project seals the
> AgMachTradeAdvisor into a single node and wraps it with an
> independent **Ag Equipment Governor**, a human **approval workflow**,
> and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers equipment-order intake through contract / sanctions /
certification regulatory verification, physical equipment dispatch and
invoice settlement. It does **not**, by itself, hold any wholesale
dealership licence or operating authority required to run an
ag-machinery-wholesale business in a given jurisdiction, and it does
not claim to. It also does not perform the actual physical loading /
transport of equipment or judge trading-book economics -- fulfillment
routing / trading-book optimization (the blueprint's own
`:optimization` technology) is a follow-up slice, not in this R0.
Whoever deploys and operates a live instance (a qualified trading
supervisor / yard operator) supplies any jurisdiction-specific
operating authority, the real transport/logistics integration and the
real ERP / accounts-receivable integrations, and bears that
jurisdiction's liability -- the software supplies the governed,
spec-cited, audited execution scaffold so that operator does not have
to build the compliance layer from scratch.

### Actuation

**Dispatching real physical farm machinery to a counterparty from the
wholesale yard/dealership and settling a real invoice are never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`agmachtrade.governor`'s `:delivery/dispatch`/`:invoice/
settle` high-stakes gate and `agmachtrade.phase`'s phase table, which
never puts either op in any phase's `:auto` set) -- see
`agmachtrade.phase`'s docstring and
`test/agmachtrade/phase_test.clj`'s
`delivery-dispatch-never-auto-at-any-phase`/
`invoice-settle-never-auto-at-any-phase`. The actor may draft, check
and recommend; a human trading supervisor is always the one who
actually dispatches physical equipment or settles an invoice. A genuine
DUAL-actuation shape, applied SEQUENTIALLY to the SAME equipment-order
(dispatch first, invoice settlement later) -- the same shape the
fuel-wholesale / metal-wholesale / agri-raw-materials siblings use.

### The certification gate: fundamentally different in kind from a trade-control gate

Every prior principal-trading sibling in this fleet gates its own
defining check on WHERE the goods came from (metal-wholesale's
conflict-minerals chain-of-custody) or WHO they are going to
(general-trading's export-license check, tech-wholesale's denied-party
check). **This vertical's defining check is neither**: it is whether
the SPECIFIC MACHINE has a valid, currently-effective PRODUCT
CERTIFICATION on file -- an EPA/EU nonroad-engine emissions certificate
(40 C.F.R. Part 1039 Tier 1-4 Final / Regulation (EU) 2016/1628 Stage
V) and a ROPS (Roll-Over Protective Structure) safety certificate (29
C.F.R. §1928.51 / the EU Machinery Directive's ROPS-equivalent
requirement). This is a PRODUCT-COMPLIANCE gate, closer in kind to a
manufacturing-certification check than a trade-control check -- see
`docs/business-model.md` for the full reasoning.

These two checks are gated on TWO INDEPENDENT boolean facts on the
equipment-order (`:engine-powered?`, `:ride-on?`), not one commodity-
type enum -- `agmachtrade.store/demo-data`'s `eo-8` (towed implement,
BOTH facts false) proves BOTH checks are true NO-OPs for a non-engine,
non-ride-on implement, and `eo-9` (a stationary/portable engine-driven
implement, `:engine-powered?` true but `:ride-on?` false) proves the
two checks fire INDEPENDENTLY of each other, not as two symptoms of one
underlying commodity-type distinction. See `agmachtrade.governor`
namespace docstring and `docs/adr/0001-architecture.md` Decision 4.

## The core contract

```
equipment-order intake + jurisdiction facts (agmachtrade.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────┐
   │ AgMachTradeAdvisor    │ ─────────────▶ │ Ag Equipment Governor  │  (independent system)
   │ (sealed)              │  + citations    │ spec-basis · evidence- │
   └───────────────────────┘                 │ incomplete · credit-   │
          │                 commit ◀┼ uncleared · contract-missing ·│
          │                         │ emissions-certificate-missing ·│
    record + ledger        escalate ┼ rops-certification-missing ·  │
          │              (ALWAYS for│ counterparty-sanctions-flag-  │
          │       :delivery/        │ unresolved · already-dispatched│
          │       dispatch/         │ · already-invoiced             │
          │       :invoice/         └───────────────────────┘
          │       settle)
          ▼
      human approval
```

**The AgMachTradeAdvisor never dispatches physical farm machinery to a
counterparty or settles an invoice the Ag Equipment Governor would
reject, and never does so without a human sign-off.** Hard violations
(fabricated regulatory requirements; unsupported evidence; an uncleared
counterparty credit; no contract-terms on file; a missing emissions
certificate for an engine-powered machine; a missing ROPS certificate
for a ride-on machine; an unresolved sanctions-screening flag; a double
dispatch/invoice) force **hold** and *cannot* be approved past; a clean
dispatch/invoice proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean dispatch + invoice lifecycle, the type-gating control pair, plus HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

`blueprint.edn` sets `:itonami.blueprint/robotics false`, and
`:itonami.blueprint/required-technologies` does NOT list `:robotics` at
all -- a deliberate, honest departure from the fuel-wholesale/
metal-wholesale siblings' own `robotics true`. Unlike a bulk-fuel
loading rack or a palletized-carton automated storage-and-retrieval
system, physical dispatch of a tractor, combine or towed implement from
an ag-equipment wholesale yard is, in real commercial practice, a
HUMAN-operated act: a self-propelled machine is driven off the lot (or
onto a transport trailer) by a human operator, and a non-self-propelled
towed implement is hitched and towed by a human-driven tow vehicle or
moved by a yard hostler under human operation. There is no comparable
fixed, robot-actuatable apparatus this actor's governor could plausibly
gate a robot command against for the entity it actually dispatches (the
equipment-order itself) -- see `docs/business-model.md` Robotics
Premise for the full reasoning, including why a tempting-but-out-of-
scope automated-parts-retrieval claim does not change this call.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Ag Equipment Governor, dispatch/invoice draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4653`). This vertical is NOT backed by a separate bespoke domain
capability lib: the ag-machinery-trading checks (credit-clearance,
contract-on-file, emissions-certification, ROPS-certification,
sanctions-screening) are direct entity boolean reads in
`agmachtrade.governor`, on top of the generic
identity/forms/dmn/bpmn/audit-ledger stack.

## Layout

| File | Role |
|---|---|
| `src/agmachtrade/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + dispatch AND invoice history (dual history). The double-actuation guard checks dedicated `:dispatched?`/`:invoiced?` booleans rather than a `:status` value |
| `src/agmachtrade/registry.cljc` | Dispatch/invoice draft records (record construction only -- the Ag Equipment Governor's checks are direct entity booleans, so there are no pure range-check functions to host here) |
| `src/agmachtrade/facts.cljc` | Per-jurisdiction generic counterparty-diligence catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/agmachtrade/agmachtradeadvisor.cljc` | **AgMachTradeAdvisor** -- `mock-advisor` ‖ `llm-advisor`; intake/certification-verification/dispatch/invoice proposals |
| `src/agmachtrade/governor.cljc` | **Ag Equipment Governor** -- 6 HARD checks (spec-basis · evidence-incomplete · credit-uncleared · contract-missing · emissions-certificate-missing · rops-certification-missing) + 1 unconditional (counterparty-sanctions-flag-unresolved) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/agmachtrade/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (dispatch/invoice always human; order intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/agmachtrade/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/agmachtrade/sim.cljc` | demo driver |
| `test/agmachtrade/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers equipment-order intake through contract / sanctions /
certification regulatory verification, physical dispatch and invoice
settlement -- the core governed lifecycle:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Equipment-order intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:order/intake`/`:certification/verify`) | Real transport/logistics integration, fulfillment routing and trading-book economics |
| Physical equipment dispatch, HARD-gated on full evidence, a credit-cleared counterparty, contract-terms on file, an engine-powered machine's emissions certificate, a ride-on machine's ROPS certificate, a passed sanctions screen and no double-dispatch (`:delivery/dispatch`) | |
| Invoice settlement, HARD-gated on full evidence, a passed sanctions screen and no double-invoice (`:invoice/settle`) | |
| Immutable audit ledger for every intake/verification/dispatch/invoice decision | |

Extending coverage is additive: add the next gate (e.g. a used-
equipment resale emissions-grandfathering check) as its own governed op
with its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any
real-world act" pattern this repo's flagship ops already establish.

## Jurisdiction coverage (honest)

`agmachtrade.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `agmachtrade.facts/catalog` --
currently 2 seeded (USA, DEU) out of ~194 jurisdictions worldwide. This
is a starting catalog to prove the governor contract end-to-end, not a
claim of global coverage. Adding a jurisdiction is additive: one map
entry in `agmachtrade.facts/catalog`, citing a real official source --
never fabricate a jurisdiction's requirements to make coverage look
bigger. See `docs/business-model.md` for an honest uncertainty flag on
the exact application date of the EU's forthcoming Machinery Regulation
and the precise OECD tractor-testing code numbering.

## Maturity

`:implemented` -- `AgMachTradeAdvisor` + `Ag Equipment Governor` run as
real, tested code (see `Run` above), following the SAME governed-actor
architecture as the other prior actors across this fleet, with its own
distinct, independently-named governor and its own direct-entity-
boolean ag-machinery-trading checks. See `docs/adr/0001-architecture.md`
for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.

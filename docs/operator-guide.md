# Operator Guide

## First Deployment
1. Register traders, trading supervisors, equipment-orders, and
   counterparties.
2. Import equipment-order, counterparty, credit, certification, and
   sanctions-screening history.
3. Seed the per-jurisdiction spec-basis catalog (`agmachtrade.facts`)
   for the jurisdictions you actually trade in, citing real official
   emissions/safety-regulator sources only.
4. Run read-only spec-basis validation per jurisdiction.
5. Configure certification / sanctions / credit escalation and
   accounts-receivable accounts.
6. Publish a dry-run dispatch/invoice and audit export.

## Minimum Trading Controls
- spec-basis validation before any verification, dispatch, or invoice
- full GENERIC counterparty-diligence evidence (credit-clearance
  record, contract/PO, sanctions-screening record) before any dispatch
- an actual, currently-effective emissions certificate on file for any
  engine-powered machine before dispatch -- never inferred, never
  defaulted
- an actual, currently-effective ROPS certificate on file for any
  ride-on machine before dispatch -- never inferred, never defaulted,
  and evaluated INDEPENDENTLY of the emissions check
- credit-clearance, contract-on-file, certification and sanctions
  checks before any dispatch; sanctions checks before any invoice
- certification / sanctions / credit escalation gate
- audit export for every dispatch, invoice, and hold
- backup manual dispatch and invoicing process

## A Day in the Life: Intake → Verify → Dispatch → Settle → Audit

Wholesale of Agricultural Machinery, Equipment and Supplies (ISIC 4653,
`cloud-itonami-isic-4653`) runs on the same intake / advise / govern /
decide / commit-or-hold loop as every itonami blueprint, but here the
loop is concrete: a regional ag-equipment dealership needs to bring an
equipment-order (say, a row-crop tractor sale to a working farm in the
US) from intake through certification verification to a physical
dispatch and an invoice settlement. Walking through it end to end:

1. **Intake.** The trader books the equipment-order through `:forms`:
   order-id, equipment-type (tractor / combine harvester / self-
   propelled sprayer / towed implement / stationary engine unit / ...),
   whether the machine is engine-powered, whether it is a ride-on
   (operator-position) type, destination jurisdiction, counterparty,
   price, contract-terms, and the order's own diligence record
   (credit-cleared?, sanctions-screened?, emissions-certificate?,
   rops-certified?). This creates an equipment-order record at
   `:order/intake` status. The AgMachTradeAdvisor only normalizes the
   patch; it does not invent the order-id, counterparty, jurisdiction,
   equipment-type, or any commercial/diligence value.
2. **Verify.** The AgMachTradeAdvisor drafts a per-jurisdiction GENERIC
   counterparty-diligence evidence checklist (`:certification/verify`)
   from `agmachtrade.facts`, citing the jurisdiction's official spec-
   basis (owner authority, legal basis, provenance) and listing the
   required evidence (credit-clearance record, contract/PO, sanctions-
   screening record). The `:ag-equipment-governor` sign-off gate must
   clear: it checks the jurisdiction actually has an official spec-
   basis on file (never invent one). A jurisdiction with no spec-basis
   is a HARD hold at the governor node -- it never even reaches a
   human. This verification always escalates to a human for approval;
   it is never auto. Separately (outside this actor, by a real
   compliance function), the machine's emissions certificate and ROPS
   certificate are obtained and the `:emissions-certificate?`/
   `:rops-certified?` facts are recorded on the order -- the governor
   independently re-reads these at dispatch time rather than trusting
   the advisor's own summary of them.
3. **Dispatch.** Before real physical farm machinery can leave the
   wholesale yard/dealership, the `:ag-equipment-governor` sign-off gate
   runs the full HARD check set against the order's own ground truth:
   the spec-basis exists, the evidence checklist is complete, the
   counterparty's credit has been cleared, contract-terms are on file,
   an engine-powered machine HAS a valid emissions certificate on file
   (`:emissions-certificate-missing` fires if not -- a genuine NO-OP for
   a non-engine towed implement), a ride-on machine HAS a valid ROPS
   certificate on file (`:rops-certification-missing` fires if not --
   evaluated INDEPENDENTLY of the emissions check, a genuine NO-OP for
   a non-ride-on implement), the counterparty has passed sanctions
   screening, and the order has not already been dispatched. Any
   failure is a HARD hold that a human cannot override. If every check
   is clean, the proposal STILL always escalates to a human trading
   supervisor -- `:delivery/dispatch` never auto-commits at any phase.
   On approval, the dispatch record (`<JURISDICTION>-DISPATCH-000001`)
   is drafted and the order's `:dispatched?` flag is set.
4. **Settle.** Once the equipment has actually been dispatched, the
   invoice is settled (`:invoice/settle`): the money side of the trade,
   custody / financial transfer. The governor re-checks the spec-basis,
   the evidence completeness, the sanctions screening, and that this
   order's invoice has not already been settled. As with the dispatch,
   a clean invoice STILL always escalates to a human -- `:invoice/
   settle` never auto-commits. On approval the invoice record is
   drafted (`<JURISDICTION>-INVOICE-000001`) and the order's
   `:invoiced?` flag is set.
5. **Audit.** The verification, the dispatch sign-off, the dispatch
   record, the invoice sign-off, and the invoice record are all
   appended to the `:audit-ledger` -- immutable and exportable, so a
   counterparty, auditor, or regulatory (including EPA or OSHA) dispute
   can be traced back to the exact spec-basis citation, evidence
   checklist, certification status, and supervisor sign-off that
   authorized the dispatch and invoice. If something is wrong with the
   counterparty or the machine (a credit deterioration, a sanctions
   hit, an expired emissions certificate, a missing ROPS certificate),
   that gets raised as a flag and routed through the escalation gate
   instead of being silently suppressed -- a dispatch for that order
   then waits on governor sign-off of the flag's resolution.

Any deviation from this loop is exactly what the Trust Controls in
`docs/business-model.md` exist to catch: an order verified against a
fabricated spec-basis, a dispatch started with incomplete evidence, an
uncleared counterparty credit or a contract gap, an engine-powered
machine dispatched with NO emissions certificate on file, a ride-on
machine dispatched with NO ROPS certificate on file, sanctions
screening suppressed to force a dispatch through, or an invoice posted
without a human sign-off.

## Feel the Decision Gate: `clojure -M:dev:run`

This vertical has no companion playable prototype. The fastest hands-on
way to feel why the `:ag-equipment-governor` gate exists -- and why it
carries TWO independently-gated certification checks rather than one --
is the bundled demo, which walks a clean tractor order through intake →
verify → dispatch → settle (each dispatch/settle pausing for human
approval), and then exercises every HARD-hold failure mode in
isolation, PLUS the two type-gating control cases:

- a jurisdiction with no official spec-basis → HOLD (`:no-spec-basis`),
- a counterparty whose credit has not been cleared → HOLD
  (`:credit-uncleared`),
- an order with no contract-terms on file → HOLD (`:contract-missing`),
- an engine-powered tractor with NO emissions certificate on file →
  HOLD (`:emissions-certificate-missing`),
- a DIFFERENT ride-on tractor with NO ROPS certificate on file → HOLD
  (`:rops-certification-missing`) -- proving these are two genuinely
  separate failure modes,
- a counterparty that has not passed OFAC-style sanctions screening →
  HOLD (`:counterparty-sanctions-flag-unresolved`),
- a towed implement (no engine, no ride-on position) with NEITHER
  certificate on file → dispatches CLEANLY -- proving both certification
  checks are true NO-OPs for a non-engine, non-ride-on implement, not a
  blanket certificate requirement silently waived,
- a stationary engine-driven implement (engine-powered but NOT ride-on)
  with NEITHER certificate on file → HOLDs on `:emissions-certificate-
  missing` but NOT on `:rops-certification-missing` -- the decisive
  proof that the two certification checks are gated on INDEPENDENT
  properties of the machine, not two symptoms of one underlying
  commodity-type distinction,
- a double dispatch of the same order → HOLD (`:already-dispatched`),
- a double invoice of the same order → HOLD (`:already-invoiced`).

Each HOLD settles at the governor node and never reaches a human
approver -- the same failure mode the audit ledger is built to catch and
the minimum trading controls above are built to prevent. It is not a
substitute for those controls, but it is the fastest way for a new
operator (or a reviewer) to feel, hands-on, why the gate exists before
touching a real deployment.

## Certification
Certified operators must prove spec-basis-grounded verification,
evidence-backed dispatch readiness (credit-clearance, contract-on-file,
emissions certification for engine-powered machinery, ROPS certification
for ride-on machinery, sanctions screening), and human review for every
dispatch- and invoice-affecting action.

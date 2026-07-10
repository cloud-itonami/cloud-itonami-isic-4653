# Governance

`cloud-itonami-isic-4653` is an OSS open-business blueprint for wholesale
of agricultural machinery, equipment and supplies.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- an equipment-order whose jurisdiction has no official spec-basis can
  never be verified, dispatched or invoiced.
- the Ag Equipment Governor remains independent of the advisor.
- hard governor violations (a fabricated spec-basis, incomplete
  counterparty-diligence evidence, an uncleared counterparty credit, a
  missing contract, a missing emissions certificate on an engine-powered
  machine, a missing ROPS certificate on a ride-on machine, an
  unresolved OFAC-style sanctions flag, a double dispatch, or a double
  invoice) cannot be overridden by human approval.
- `emissions-certificate-missing` and `rops-certification-missing`
  remain two SEPARATE checks, gated on independent facts -- never
  collapsed back into one boolean.
- every intake, certification verification, dispatch, settlement and
  hold is auditable.
- counterparty, credit, certification and sanctions-screening data
  stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:
- bypassing dispatch or invoice-settlement policy checks
- mishandling counterparty, credit, certification, or sanctions-
  screening data
- misrepresenting certification status
- failing to respond to security incidents

# Contributing

`cloud-itonami-isic-4653` accepts contributions to the OSS blueprint, the
Ag Equipment Governor, decision-rule tests, documentation and operator
model.

## Development
The capability layer is SELF-CONTAINED. There is no pre-existing bespoke
agricultural-machinery-wholesale capability library to wrap; the
counterparty-credit / contract-on-file / emissions-certification / ROPS-
certification / sanctions-screening checks live directly in
`agmachtrade.governor`. This repo holds the business blueprint, the
langgraph-clj actor and the operator contracts.

```bash
clojure -M:dev:test
clojure -M:lint
```

## Rules
- Do not commit real counterparty, credit, certification, or sanctions-
  screening data.
- Keep physical dispatch and invoice settlement behind the Ag Equipment
  Governor.
- Treat certification workflows as high-risk: add tests for spec-basis,
  evidence completeness, credit clearance, contract-on-file, emissions
  certification, ROPS certification, sanctions screening and audit
  logging.
- Keep `emissions-certificate-missing` and `rops-certification-missing`
  as two SEPARATE checks, gated on their own independent
  `:engine-powered?`/`:ride-on?` facts -- do not collapse them back into
  one boolean (see `docs/adr/0001-architecture.md` Decision 4 for why).
- Never fabricate a jurisdiction's agricultural-machinery-wholesale /
  emissions / safety requirements in `agmachtrade.facts` -- cite a real
  official source or leave the jurisdiction out of the catalog.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which governor invariant is
affected, how it was tested, whether operator or certification docs need
updates.

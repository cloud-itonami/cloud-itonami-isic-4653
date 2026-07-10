(ns agmachtrade.facts
  "Per-jurisdiction downstream agricultural-machinery-wholesale
  regulatory catalog -- the G2-style spec-basis table the Ag Equipment
  Governor checks every `:certification/verify` proposal against ('did
  the advisor cite an OFFICIAL public source for this jurisdiction's
  agricultural-machinery trade / nonroad-engine-emissions / tractor-
  safety requirements, or did it invent one?').

  UNLIKE every prior wholesale-trading sibling in this fleet (fuel,
  metal, agri raw materials, tech, general trade -- each gated on
  counterparty-diligence evidence PLUS a commodity/order-property check
  that is either jurisdiction-independent (metal's conflict-minerals-
  metal? gate) or a kind enum split (agri's plant/animal gate)), THIS
  vertical's own defining regulatory content is a PRODUCT-CERTIFICATION
  gate, not a trade-control gate: whether the SPECIFIC machine has a
  valid, currently-effective emissions certificate and/or ROPS (Roll-
  Over Protective Structure) safety certificate on file, evaluated
  against TWO INDEPENDENT boolean facts on the machine itself
  (`:engine-powered?`, `:ride-on?`) rather than a single commodity-type
  enum. This catalog therefore stays deliberately GENERIC -- the same
  shape as the fuel-wholesale sibling's own catalog (credit-clearance
  record, contract/PO, sanctions-screening record) -- and does NOT fold
  the emissions/ROPS-certification facts into a per-jurisdiction
  checklist item: those are `agmachtrade.governor`'s own two dedicated,
  INDEPENDENTLY type-gated HARD checks
  (`emissions-certificate-missing-violations` /
  `rops-certification-missing-violations`), not a jurisdiction-catalog
  checklist entry. See `agmachtrade.governor` namespace docstring for
  the full reasoning on why these are two SEPARATE checks rather than
  one folded rule.

  Each entry below is a REAL jurisdiction with a REAL downstream
  agricultural-machinery-wholesale regime, cited for the emissions AND
  safety regulatory framework specifically (not merely a generic customs
  statute):

  - USA (the PRIMARY regime for this vertical): the U.S. Environmental
    Protection Agency (EPA) administers 40 C.F.R. Part 1039 (Control of
    Emissions from New and In-Use Nonroad Compression-Ignition Engines),
    the regulation that carries the Tier 1 through Tier 4 Final nonroad
    diesel emission standards agricultural/off-road diesel engines
    (tractors, combines, self-propelled sprayers) must be EPA-certified
    to meet before sale, issued as an Engine Family certificate. The
    Occupational Safety and Health Administration (OSHA) administers 29
    C.F.R. §1928.51 (Roll-Over Protective Structures for tractors used
    in agricultural operations), requiring a ROPS on any agricultural
    tractor MANUFACTURED AFTER October 25, 1976 -- the operator-ride
    tractor-safety regime this actor's `:rops-certified?` fact is
    grounded in. ASAE/SAE S519 (Roll-Over Protective Structures (ROPS)
    for Wheeled Agricultural Tractors) is the design/test standard OSHA's
    rule references for what counts as a compliant ROPS. OFAC sanctions
    programs apply as in every sibling's own counterparty-diligence
    checklist.
  - DEU (the EU-representative jurisdiction; Regulation (EU) 2016/1628
    is directly applicable in every EU member state, so this entry
    stands in for the EU regime generally): the Kraftfahrt-Bundesamt
    (KBA), operating within the EU type-approval framework, administers
    Regulation (EU) 2016/1628 (the EU 'Stage V' nonroad mobile machinery
    emission standard) -- the real EU analog of the US Tier system,
    covering the SAME class of nonroad compression-ignition engines
    (agricultural tractors, combines, self-propelled machinery) via an
    engine-family type-approval mechanic structurally parallel to the US
    EPA's own Engine Family certificate. Tractor/machinery safety
    (including the ROPS-equivalent essential requirement) is CURRENTLY
    governed by Machinery Directive 2006/42/EC (harmonized EN ISO
    standards, including EN ISO 3471, satisfy its Annex I essential
    health-and-safety requirements for rollover protection) -- the
    successor Machinery Regulation (EU) 2023/1230 has been adopted but
    this build is NOT confident of its exact application date relative
    to 2006/42/EC's repeal (flagged in `docs/business-model.md`
    'Jurisdiction coverage (honest)' for independent verification). EU
    financial sanctions regulations apply as in every sibling's own
    counterparty-diligence checklist.

  Internationally (outside the two seeded jurisdictions above), ISO
  3471 (Earth-moving machinery -- Roll-over protective structures --
  Laboratory tests and performance requirements) and the OECD's Standard
  Codes for the Official Testing of Agricultural and Forestry Tractors
  (OECD Code 4 covers static ROPS testing) are the internationally-
  referenced ROPS test standards a manufacturer's certificate typically
  cites -- this build's confidence in the EXACT OECD code number and its
  narrow-track/wide-track scope is lower than its confidence in the two
  seeded jurisdictions' own statutory citations above, so it is named
  here for context but NOT relied on as an independent jurisdiction
  entry (see honest coverage note).

  The required-evidence set (credit-clearance record, contract/PO,
  sanctions-screening (OFAC/equivalent) record) mirrors the GENERIC
  counterparty-diligence evidence every principal-trading sibling's own
  catalog demands before ANY order proceeds -- it deliberately does NOT
  include an emissions-certificate or ROPS-certificate item: unlike a
  jurisdiction-scoped checklist entry, THIS vertical's certification
  gate is evaluated by two SEPARATE, dedicated, TYPE-GATED governor
  checks (see `agmachtrade.governor`), not a checklist item.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` is the GENERIC
  counterparty-diligence evidence set (credit-clearance record,
  contract/PO, sanctions-screening record); `:legal-basis` /
  `:owner-authority` / `:provenance` are the G2 citation the governor
  requires before any `:certification/verify` proposal can commit.
  Deliberately does NOT include an emissions/ROPS-certificate checklist
  item -- those are `agmachtrade.governor`'s own two dedicated,
  independently type-gated HARD checks."
  {"USA" {:name "USA"
          :owner-authority "U.S. Environmental Protection Agency (EPA), Office of Transportation and Air Quality / Occupational Safety and Health Administration (OSHA)"
          :legal-basis "40 C.F.R. Part 1039 (nonroad compression-ignition engine emission standards, Tier 1-4 Final, Engine Family certification); 29 C.F.R. §1928.51 (Roll-Over Protective Structures for agricultural tractors manufactured after October 25, 1976; ASAE/SAE S519 design/test standard); OFAC sanctions programs"
          :provenance "https://www.epa.gov/regulations-emissions-vehicles-and-engines/regulations-emissions-nonroad-diesel-engines"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}
   "DEU" {:name "DEU"
          :owner-authority "Kraftfahrt-Bundesamt (KBA) / EU type-approval framework"
          :legal-basis "Regulation (EU) 2016/1628 (Stage V nonroad mobile machinery emission standards, engine-family type-approval); Machinery Directive 2006/42/EC (harmonized ROPS-equivalent essential safety requirements, EN ISO 3471); EU financial sanctions regulations"
          :provenance "https://eur-lex.europa.eu/eli/reg/2016/1628/oj"
          :required-evidence ["credit-clearance record"
                              "contract/PO"
                              "sanctions-screening (OFAC/equivalent) record"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to dispatch
  equipment or settle an invoice on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-4653 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `agmachtrade.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(ns agmachtrade.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo shipped a `docs/samples/operator-console.html` that NO
  generator had ever produced -- it was hand-written, and nothing but
  actually running a generator could detect that. This namespace drives
  the REAL actor stack (`agmachtrade.operation` -> `agmachtrade.governor`
  -> `agmachtrade.store`, through `langgraph.graph/run*`) and renders
  whatever that run actually produced.

  Nothing on the page is typed by hand:
    - equipment-order rows come from `agmachtrade.store/all-equipment-orders`
      AFTER the run (so `:dispatched?`/`:invoiced?`/`:dispatch-number`/
      `:invoice-number` are the post-run SSoT, not a prediction);
    - the action-gate rows are produced by CALLING `agmachtrade.phase/gate`
      with a clean and with a held governor verdict;
    - the rollout-phase rows are read straight out of
      `agmachtrade.phase/phases`;
    - the HARD-check rows are grouped out of the run's real
      `:governor-hold` facts, detail text included (the governor writes it);
    - the certification type-gating matrix is derived by joining each
      order's OWN `:engine-powered?`/`:ride-on?`/`:emissions-certificate?`/
      `:rops-certified?` facts against the rules that actually fired for
      it -- the two-independent-gates claim is MEASURED here, not asserted;
    - the jurisdiction rows come from `agmachtrade.facts/coverage` over the
      jurisdictions the seeded orders actually carry;
    - the approver column is read back OUT of the store at render time;
    - the registry drafts are the records `agmachtrade.registry` actually
      minted.

  Scenario shape (adapted from this repo's own `agmachtrade.sim` demo
  driver, `clojure -M:dev:run`, which was run BEFORE this file was written
  and confirmed to produce a sensible ledger against the real seeded
  equipment-order ids `eo-1`..`eo-9`):

    - eo-1 (Akita Agri Machinery Trading Co, USA tractor) walks a full
      clean lifecycle -- intake (auto-commit at phase 3, no capital risk),
      certification verification (escalates, approved), physical dispatch
      and invoice settlement (BOTH always escalate at every phase, both
      approved) -- then HARD-holds a double dispatch and a double invoice.
    - eo-2..eo-7 each isolate exactly ONE HARD failure mode.
    - eo-8 (towed implement) and eo-9 (stationary engine unit) are the
      CONTROL PAIR that proves the emissions and ROPS gates fire
      independently of each other rather than as two faces of one
      commodity-type test.
    - eo-8's invoice settlement is REJECTED by the human approver -- the
      SOFT gate's other outcome, to contrast with a HARD hold that never
      reaches a human at all.

  Build-time invariants (`-main` THROWS, it does not warn):
    1. the run must produce at least one real `:governor-hold` fact;
    2. it must exercise at least `min-distinct-hard-rules` DISTINCT HARD
       rules -- an evidence floor, so a governor change that silently
       stops firing a check fails the build instead of quietly shrinking
       the page;
    3. no run that HARD-held may also have asked a human (no
       `:approval-requested` in the same run's audit) -- the 'HARD holds
       never reach a human' claim is measured, not asserted;
    4. every HARD-check row rendered must trace to a rule present in the
       ledger (the section cannot outlive the run that justified it);
    5. the SOFT gate must have shown BOTH outcomes (an approval granted
       AND an approval rejected) -- otherwise the approval trail and the
       approver-attribution probe are both vacuous, and an empty table
       reads exactly like a passing one;
    6. the type-gating control pair must actually hold: SOME order that is
       engine-powered but not ride-on, carrying NEITHER certificate, must
       fire the emissions rule and NOT the ROPS rule; and SOME order that
       is neither engine-powered nor ride-on, carrying NEITHER
       certificate, must dispatch anyway. Stated structurally, not by id,
       so renaming a demo order cannot silently retire the check.

  Deterministic: no timestamps, no random ids, byte-identical across
  reruns against the same seed (verify by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [agmachtrade.store :as store]
            [agmachtrade.facts :as facts]
            [agmachtrade.phase :as phase]
            [agmachtrade.governor :as governor]
            [agmachtrade.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :trading-supervisor :phase 3})

(def min-distinct-hard-rules
  "Evidence floor for invariant 2. The scenario below drives nine distinct
  HARD governor rules -- every check `agmachtrade.governor/check` can
  emit. If a governor/store/advisor change makes fewer of them fire, the
  build fails rather than rendering a quietly thinner page. Raise this
  when the scenario grows; never lower it to make a build pass."
  9)

;; ----------------------------- the real run -----------------------------

(defn- exec!
  "One supervised operation = one langgraph run. Returns the run result;
  `runs` accumulates them so the renderer can read each run's own audit
  channel (the approval facts never reach the store ledger -- see
  `agmachtrade.operation`'s `:commit` node, which appends only the commit
  fact)."
  [runs actor tid request]
  (let [r (g/run* actor {:request request :context operator} {:thread-id tid})]
    (swap! runs conj (assoc (select-keys request [:op :subject]) :thread tid
                            :audit (get-in r [:state :audit])
                            :disposition (get-in r [:state :disposition])))
    r))

(defn- resume!
  "Resume a run paused at `:request-approval` with a human decision."
  [runs actor tid status by]
  (let [r (g/run* actor {:approval {:status status :by by}}
                  {:thread-id tid :resume? true})]
    (swap! runs conj {:thread tid :resume? true
                      :audit (get-in r [:state :audit])
                      :disposition (get-in r [:state :disposition])})
    r))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach. Returns `{:db .. :runs [..]}` -- every field the
  renderer reads below is real governor/store/langgraph output.

  Each HARD failure mode is exercised DIRECTLY on its own order, never
  only as a side effect of a happy-path actuation -- the discipline
  `agmachtrade.sim`'s docstring records as established by `parksafety`'s
  ADR-2607071922 Decision 5. Orders eo-3..eo-7 have their certification
  verified and approved FIRST, so that when their dispatch is held the
  hold is attributable to the one fact that order isolates rather than to
  a missing evidence checklist."
  []
  (let [db (store/seed-db)
        runs (atom [])
        actor (op/build db)]

    ;; --- eo-1: full clean lifecycle -----------------------------------
    (exec! runs actor "eo1-intake"
           {:op :order/intake :subject "eo-1"
            :patch {:id "eo-1" :counterparty "Akita Agri Machinery Trading Co"}})

    (exec! runs actor "eo1-verify" {:op :certification/verify :subject "eo-1"})
    (resume! runs actor "eo1-verify" :approved "op-1")

    (exec! runs actor "eo1-dispatch" {:op :delivery/dispatch :subject "eo-1"})
    (resume! runs actor "eo1-dispatch" :approved "op-1")

    (exec! runs actor "eo1-settle" {:op :invoice/settle :subject "eo-1"})
    (resume! runs actor "eo1-settle" :approved "op-1")

    ;; --- HARD: jurisdiction with no official spec-basis ---------------
    ;; eo-2 is seeded in the unregistered jurisdiction "ATL", so the
    ;; advisor finds no spec-basis and cites nothing.
    (exec! runs actor "eo2-verify" {:op :certification/verify :subject "eo-2"})

    ;; --- HARD: actuation before the evidence checklist is on file -----
    ;; eo-2's verification never committed, so it has no assessment.
    (exec! runs actor "eo2-dispatch" {:op :delivery/dispatch :subject "eo-2"})

    ;; --- HARD: counterparty credit not cleared ------------------------
    (exec! runs actor "eo3-verify" {:op :certification/verify :subject "eo-3"})
    (resume! runs actor "eo3-verify" :approved "op-1")
    (exec! runs actor "eo3-dispatch" {:op :delivery/dispatch :subject "eo-3"})

    ;; --- HARD: no contract-terms on file ------------------------------
    (exec! runs actor "eo4-verify" {:op :certification/verify :subject "eo-4"})
    (resume! runs actor "eo4-verify" :approved "op-1")
    (exec! runs actor "eo4-dispatch" {:op :delivery/dispatch :subject "eo-4"})

    ;; --- HARD: sanctions screening not passed -------------------------
    (exec! runs actor "eo5-verify" {:op :certification/verify :subject "eo-5"})
    (resume! runs actor "eo5-verify" :approved "op-1")
    (exec! runs actor "eo5-dispatch" {:op :delivery/dispatch :subject "eo-5"})

    ;; --- HARD: engine-powered, no emissions certificate ---------------
    (exec! runs actor "eo6-verify" {:op :certification/verify :subject "eo-6"})
    (resume! runs actor "eo6-verify" :approved "op-1")
    (exec! runs actor "eo6-dispatch" {:op :delivery/dispatch :subject "eo-6"})

    ;; --- HARD: ride-on, no ROPS certificate (a DIFFERENT rule) --------
    (exec! runs actor "eo7-verify" {:op :certification/verify :subject "eo-7"})
    (resume! runs actor "eo7-verify" :approved "op-1")
    (exec! runs actor "eo7-dispatch" {:op :delivery/dispatch :subject "eo-7"})

    ;; --- CONTROL A: towed implement, NEITHER certificate, dispatches --
    (exec! runs actor "eo8-verify" {:op :certification/verify :subject "eo-8"})
    (resume! runs actor "eo8-verify" :approved "op-1")
    (exec! runs actor "eo8-dispatch" {:op :delivery/dispatch :subject "eo-8"})
    (resume! runs actor "eo8-dispatch" :approved "op-1")

    ;; --- SOFT: clean proposal, human approver says NO -----------------
    (exec! runs actor "eo8-settle" {:op :invoice/settle :subject "eo-8"})
    (resume! runs actor "eo8-settle" :rejected "op-1")

    ;; --- CONTROL B: engine but NOT ride-on -> emissions rule only -----
    (exec! runs actor "eo9-verify" {:op :certification/verify :subject "eo-9"})
    (resume! runs actor "eo9-verify" :approved "op-1")
    (exec! runs actor "eo9-dispatch" {:op :delivery/dispatch :subject "eo-9"})

    ;; --- HARD: double dispatch / double invoice of the same order -----
    (exec! runs actor "eo1-dispatch-again" {:op :delivery/dispatch :subject "eo-1"})
    (exec! runs actor "eo1-settle-again" {:op :invoice/settle :subject "eo-1"})

    {:db db :runs @runs}))

;; ----------------------------- helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kname [k] (if (keyword? k) (name k) (str k)))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- code* [v] (str "<code>" (esc v) "</code>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- yn [b klass-true klass-false t f]
  (if b (str "<span class=\"" klass-true "\">" t "</span>")
      (str "<span class=\"" klass-false "\">" f "</span>")))

(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))

;; ----------------------------- derived views -----------------------------

(defn- hard-holds [ledger] (filter #(= :governor-hold (:t %)) ledger))

(defn- hard-rule-groups
  "Groups the run's REAL `:governor-hold` facts by violated rule, in
  first-seen ledger order. Nothing here is a literal: if a rule stops
  firing its row disappears (and `-main`'s evidence floor fails)."
  [ledger]
  (->> (for [f (hard-holds ledger)
             v (:violations f)]
         (assoc v :subject (:subject f) :op (:op f)))
       (reduce (fn [acc {:keys [rule detail subject op]}]
                 (if (contains? acc rule)
                   (-> acc
                       (update-in [rule :count] inc)
                       (update-in [rule :subjects] conj subject)
                       (update-in [rule :ops] conj op))
                   (assoc acc rule {:rule rule :detail detail :count 1
                                    :order (count acc)
                                    :subjects [subject] :ops [op]})))
               {})
       vals
       (sort-by :order)))

(defn- rules-fired-for
  "The set of HARD rules the run actually recorded against `subject` for
  `op`. Read out of the ledger -- never predicted from the order's
  fields, which is the whole point of the type-gating matrix below."
  [ledger subject op]
  (into #{}
        (for [f (hard-holds ledger)
              :when (and (= subject (:subject f)) (= op (:op f)))
              v (:violations f)]
          (:rule v))))

(defn- dispatch-attempts
  "Every order this run actually ATTEMPTED to dispatch, in ledger order,
  with the rules that fired and whether the SSoT now records a dispatch.
  Drives the type-gating matrix: an order nobody tried to dispatch has
  nothing to say about which gate fires."
  [db ledger]
  (let [attempted (->> ledger
                       (filter #(= :delivery/dispatch (:op %)))
                       (map :subject)
                       distinct
                       vec)]
    (for [id attempted
          :let [eo (store/equipment-order db id)
                fired (rules-fired-for ledger id :delivery/dispatch)]]
      (assoc eo
             :fired fired
             :emissions-fired? (contains? fired :emissions-certificate-missing)
             :rops-fired? (contains? fired :rops-certification-missing)))))

;; --- store approver attribution, DERIVED (never asserted) -------------

(defn- approver-in
  "Any value stored under an `approved-by` / `approved_by` key, whatever
  the key type (this store mixes keyword-keyed maps -- the certification
  assessment payload -- with `agmachtrade.registry`'s string-keyed
  records)."
  [m]
  (when (map? m)
    (some (fn [[k v]]
            (when (and v (contains? #{"approved-by" "approved_by"} (kname k))) v))
          m)))

(defn- registers-for
  "The SSoT registers a committed op of this kind actually writes, read
  back through the Store protocol. This is the probe: whether the
  approver survives is decided by what is IN these maps at render time,
  not by anything this namespace claims."
  [db op subject]
  (case op
    :order/intake         [(store/equipment-order db subject)]
    :certification/verify [(store/assessment-of db subject)]
    :delivery/dispatch
    (into [(store/equipment-order db subject)]
          (filter #(= subject (get % "equipment_order_id")) (store/dispatch-history db)))
    :invoice/settle
    (into [(store/equipment-order db subject)]
          (filter #(= subject (get % "equipment_order_id")) (store/invoice-history db)))
    []))

(defn- register-names [op]
  (case op
    :order/intake         "equipment-order record (:order/upsert value)"
    :certification/verify "certification assessment (:certification-assessment/set payload)"
    :delivery/dispatch    "equipment-order record + equipment-dispatch draft"
    :invoice/settle       "equipment-order record + equipment-invoice draft"
    ""))

(defn- approval-trail
  "One row per human decision the run actually took. `:decided-by` is
  read off the run's own audit fact; `:retained` is read back OUT of the
  store. The two are reported separately on purpose -- a reader must be
  able to tell 'nobody approved' from 'the store dropped it'."
  [db runs]
  (let [by-thread (into {} (for [r runs :when (not (:resume? r))]
                             [(:thread r) r]))]
    (for [r runs
          :when (:resume? r)
          :let [f (last (filter #(#{:approval-granted :approval-rejected} (:t %)) (:audit r)))
                src (get by-thread (:thread r))
                o (:op src)
                s (:subject src)]
          :when f]
      {:thread (:thread r)
       :op o
       :subject s
       :outcome (:t f)
       :decided-by (:by f)
       :register (register-names o)
       :retained (when (= :approval-granted (:t f))
                   (some approver-in (registers-for db o s)))})))

(defn- approver-disclosure
  "The attribution sentence, DERIVED from the trail this run actually
  produced rather than stated as prose. Which registers keep the approver
  is a property of `agmachtrade.store/commit-record!`, not of this page:
  the `:certification-assessment/set` branch persists `:payload` whole
  (and `agmachtrade.operation`'s `:request-approval` node puts
  `:approved-by` into exactly that payload), while the two actuation
  branches re-mint their record from the equipment-order id and
  jurisdiction via `agmachtrade.registry` and never read `:payload` at
  all. If that is changed, this sentence changes with it -- a hard-coded
  claim here would have become a lie the moment someone fixed the store."
  [trail]
  (let [granted (filter #(= :approval-granted (:outcome %)) trail)
        ops (fn [xs] (str/join ", " (map #(code* %) (distinct (map :op xs)))))
        kept (filter :retained granted)
        lost (remove :retained granted)]
    (cond
      (empty? granted)
      "No approval was granted in this run, so there is nothing to attribute."

      (empty? lost)
      (str "Measured on this run: the approver survived on every register written by "
           (ops kept) ".")

      (empty? kept)
      (str "Measured on this run: the approver was dropped by every register written by "
           (ops lost) " — it exists only on the audit fact, which is why the column above "
           "says <em>audit only</em> rather than going blank.")

      :else
      (str "Measured on this run: the approver survived on the payload-backed register written by "
           (ops kept) ", and was dropped by " (ops lost)
           " — those records are re-minted by <code>agmachtrade.registry</code> from the "
           "equipment-order id and jurisdiction alone, so the <code>:payload</code> carrying "
           "<code>approved-by</code> is never read. Where it was dropped the approver is joined "
           "back in from the audit fact and labelled as such; it is never silently omitted, "
           "because silence would make “nobody approved” and “the store dropped it” look identical."))))

;; ----------------------------- rendering -----------------------------

(defn- last-fact-for [ledger id]
  (last (filter #(= (:subject %) id) ledger)))

(defn- status-cell [ledger id]
  (let [f (last-fact-for ledger id)]
    (cond
      (nil? f) (muted "no activity")
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (str/join ", " (map kname (:basis f)))) "</span>")
      (= :approval-rejected (:t f)) "<span class=\"warn\">approver rejected</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      :else (muted "in progress"))))

(defn- order-row [ledger {:keys [id order-id equipment-type counterparty jurisdiction
                                 engine-powered? ride-on?
                                 credit-cleared? contract-terms sanctions-screened?
                                 emissions-certificate? rops-certified?
                                 dispatched? invoiced? dispatch-number invoice-number]}]
  (row (code* id)
       (code* order-id)
       (esc (kname equipment-type))
       (esc counterparty)
       (esc jurisdiction)
       (str (yn engine-powered? "warn" "muted" "engine" "no engine") " / "
            (yn ride-on? "warn" "muted" "ride-on" "no ride-on"))
       (str (yn credit-cleared? "ok" "critical" "credit" "credit") " "
            (yn (and contract-terms (not= "" contract-terms)) "ok" "critical" "contract" "contract") " "
            (yn sanctions-screened? "ok" "critical" "sanctions" "sanctions"))
       (str (yn emissions-certificate? "ok" "critical" "emissions" "emissions") " "
            (yn rops-certified? "ok" "critical" "ROPS" "ROPS"))
       (if dispatched?
         (str "<span class=\"ok\">dispatched &middot; " (code* dispatch-number) "</span>")
         (muted "not dispatched"))
       (if invoiced?
         (str "<span class=\"ok\">invoiced &middot; " (code* invoice-number) "</span>")
         (muted "not invoiced"))
       (status-cell ledger id)))

(defn- phase-row [[p {:keys [label writes auto]}]]
  (row (str "<span class=\"num\">" p "</span>")
       (esc label)
       (if (seq writes)
         (str/join " " (map #(code* %) (sort-by str writes)))
         (muted "none"))
       (if (seq auto)
         (str/join " " (map #(code* %) (sort-by str auto)))
         (muted "none &middot; every write needs a human"))))

(defn- op-gate-row
  "Calls the REAL `agmachtrade.phase/gate` at the default phase for both a
  governor-clean and a governor-held base disposition, so the two middle
  columns are the gate's own answers rather than a description of them."
  [op]
  (let [clean (phase/gate phase/default-phase {:op op} :commit)
        held (phase/gate phase/default-phase {:op op} :hold)
        stakes? (contains? governor/high-stakes op)]
    (row (code* op)
         (case (:disposition clean)
           :commit "<span class=\"ok\">auto-commit when governor-clean</span>"
           :escalate (str "<span class=\"warn\">human approval</span>"
                          (when (:reason clean) (str " " (muted (str "&middot; " (esc (kname (:reason clean))))))))
           (str "<span class=\"critical\">hold</span>"
                (when (:reason clean) (str " " (muted (str "&middot; " (esc (kname (:reason clean)))))))))
         (str "<span class=\"critical\">" (esc (kname (:disposition held))) "</span>")
         (if stakes?
           "<span class=\"critical\">yes &middot; never auto at any phase</span>"
           (muted "no")))))

(defn- hard-check-row [{:keys [rule detail count subjects]}]
  (row (code* rule)
       (str "<span class=\"num\">" count "</span>")
       (str/join " " (map #(code* %) (distinct subjects)))
       (esc detail)))

(defn- type-gate-row [{:keys [id equipment-type engine-powered? ride-on?
                              emissions-certificate? rops-certified?
                              emissions-fired? rops-fired? dispatched?]}]
  (row (code* id)
       (esc (kname equipment-type))
       (yn engine-powered? "warn" "muted" "yes" "no")
       (yn ride-on? "warn" "muted" "yes" "no")
       (yn emissions-certificate? "ok" "critical" "on file" "missing")
       (yn rops-certified? "ok" "critical" "on file" "missing")
       (if emissions-fired?
         "<span class=\"critical\">fired</span>"
         (muted "silent"))
       (if rops-fired?
         "<span class=\"critical\">fired</span>"
         (muted "silent"))
       (if dispatched?
         "<span class=\"ok\">dispatched</span>"
         "<span class=\"critical\">held</span>")))

(defn- jurisdiction-row [iso3]
  (let [sb (facts/spec-basis iso3)]
    (row (code* iso3)
         (if sb (esc (:name sb)) "<span class=\"critical\">no spec-basis on file</span>")
         (if sb (esc (:owner-authority sb)) (muted "&mdash;"))
         (if sb (esc (:legal-basis sb)) (muted "&mdash;"))
         (str "<span class=\"num\">" (count (:required-evidence sb)) "</span>")
         (if sb (code* (:provenance sb))
             "<span class=\"critical\">proposals citing it are HARD-held</span>"))))

(defn- approval-row [{:keys [op subject outcome decided-by register retained]}]
  (row (code* op)
       (code* subject)
       (if (= :approval-granted outcome)
         "<span class=\"ok\">approved</span>"
         "<span class=\"warn\">rejected</span>")
       (if decided-by (code* decided-by)
           "<span class=\"warn\">not recorded on the audit fact</span>")
       (esc register)
       (cond
         (not= :approval-granted outcome) (muted "n/a &middot; nothing committed")
         retained (str "<span class=\"ok\">retained &middot; " (code* retained) "</span>")
         decided-by (str "<span class=\"warn\">" (code* decided-by)
                         " (audit only — not retained in the store record)</span>")
         :else "<span class=\"warn\">unavailable in both the store and the audit fact</span>")))

(defn- draft-row [r]
  (row (code* (get r "record_id"))
       (esc (get r "kind"))
       (code* (get r "equipment_order_id"))
       (esc (get r "jurisdiction"))
       (yn (get r "immutable") "ok" "warn" "immutable" "mutable")))

(defn- ledger-row [{:keys [t op subject basis summary]}]
  (row (case t
         :committed "<span class=\"ok\">committed</span>"
         :governor-hold "<span class=\"critical\">governor-hold</span>"
         :approval-rejected "<span class=\"warn\">approval-rejected</span>"
         (muted (esc (kname t))))
       (code* (or op :n-a))
       (code* subject)
       (esc (str/join ", " (map kname basis)))
       (esc (or summary ""))))

(defn- section [title lede headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" body-rows "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn render
  "Renders the whole document from a `{:db .. :runs ..}` produced by
  `run-demo!` (or any other real scenario)."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        orders (store/all-equipment-orders db)
        used-jurisdictions (vec (distinct (map :jurisdiction orders)))
        cov (facts/coverage used-jurisdictions)
        groups (hard-rule-groups ledger)
        attempts (dispatch-attempts db ledger)
        trail (approval-trail db runs)
        n-hard (count (hard-holds ledger))]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-4653 &middot; agricultural-machinery wholesale operator console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Wholesale of agricultural machinery, equipment and supplies (ISIC 4653) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · equipment dispatch and invoice settlement are always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "Equipment orders (SSoT snapshot after the run)"
      (str "Every row is <code>agmachtrade.store/all-equipment-orders</code> read back AFTER the run — "
           "build-time-generated by <code>agmachtrade.render-html</code> "
           "(<code>clojure -M:dev:render-html</code>), never hand-written. "
           "The dispatch and invoice numbers are the ones <code>agmachtrade.registry</code> actually minted; "
           "an order with no number never got past the governor. The certificate columns are the order's OWN "
           "recorded facts — the governor re-reads them independently at dispatch time rather than trusting "
           "the advisor's summary.")
      ["Order" "Order id" "Type" "Counterparty" "Juris." "Machine facts"
       "Counterparty diligence" "Certificates" "Dispatch" "Invoice" "Last op status"]
      (rows (map (partial order-row ledger) orders)))

     (section
      "Action gate — what the phase gate actually answers"
      (str "The two middle columns are produced by CALLING <code>agmachtrade.phase/gate</code> at phase "
           phase/default-phase " (<code>" (esc (:label (get phase/phases phase/default-phase))) "</code>) "
           "with a clean and with a held governor verdict. A HARD governor violation always stays a hold — "
           "the phase gate can only add caution, never remove it. The right-most column is membership of "
           "<code>agmachtrade.governor/high-stakes</code>: two independent layers agree that dispatching real "
           "physical farm machinery off the yard and settling a real invoice are always a human call.")
      ["Op" "Governor clean →" "Governor HARD →" "High-stakes actuation"]
      (rows (map op-gate-row (sort-by str phase/write-ops))))

     (section
      "Rollout phases"
      (str "Read straight out of <code>agmachtrade.phase/phases</code>. Note that "
           "<code>:delivery/dispatch</code> and <code>:invoice/settle</code> appear in no phase's auto set, "
           "including the last one — a permanent structural fact, not a milestone still to come.")
      ["Phase" "Label" "Writes allowed" "May auto-commit when governor-clean"]
      (rows (map phase-row (sort-by key phase/phases))))

     (section
      (str "HARD governor holds fired by this run (" n-hard " holds, " (count groups) " distinct rules)")
      (str "Grouped out of the run's real <code>:governor-hold</code> facts, detail text included — the "
           "governor writes that text, this page does not. A HARD violation is un-overridable: it never "
           "reaches a human approver at all, which the build verifies structurally (no "
           "<code>:approval-requested</code> in any held run's audit) rather than claiming here. "
           "The build also fails if fewer than <span class=\"num\">" min-distinct-hard-rules
           "</span> distinct rules fire, so a check that silently stops firing breaks the build instead "
           "of quietly shrinking this table.")
      ["Rule" "Times fired" "Orders" "Detail (from the governor)"]
      (rows (map hard-check-row groups)))

     (section
      "Certification type-gating — measured, not asserted"
      (str "This vertical's defining claim is that the emissions gate and the ROPS gate are gated on TWO "
           "INDEPENDENT booleans on the machine itself (<code>:engine-powered?</code>, "
           "<code>:ride-on?</code>) rather than on one commodity-type enum. The two right-hand columns are "
           "not predicted from the left-hand ones: they are read out of the rules that actually appear in "
           "this run's <code>:governor-hold</code> facts for each order's dispatch attempt. Read the two "
           "control rows together — an order with NEITHER certificate on file that is neither engine-powered "
           "nor ride-on dispatches anyway (both gates are true NO-OPs, not silently forgiven), while an order "
           "with NEITHER certificate that is engine-powered but NOT ride-on fires the emissions rule and "
           "leaves the ROPS rule silent. One row alone could not distinguish two independent gates from one "
           "shared type test; the pair can. The build asserts this structurally, by machine facts rather "
           "than by order id.")
      ["Order" "Type" "Engine-powered?" "Ride-on?" "Emissions cert" "ROPS cert"
       "Emissions rule" "ROPS rule" "Outcome"]
      (rows (map type-gate-row attempts)))

     (section
      "Jurisdiction spec-basis coverage"
      (str "<code>agmachtrade.facts/coverage</code> over the jurisdictions the seeded orders actually carry: "
           "<span class=\"num\">" (:covered cov) "</span> of <span class=\"num\">" (:requested cov)
           "</span> covered"
           (when (seq (:missing-jurisdictions cov))
             (str ", missing " (str/join ", " (map #(code* %) (:missing-jurisdictions cov)))))
           ". " (esc (:note cov)))
      ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Required evidence" "Provenance"]
      (rows (map jurisdiction-row (sort used-jurisdictions))))

     (section
      "Human approval trail — and what the store actually kept"
      (str "Left half is the run's own audit fact; the right-most column is read back OUT of the store at "
           "render time by looking for an <code>approved-by</code> key in the registers that op writes. "
           "It is derived, not declared: if the store is later changed to keep the approver on a register it "
           "currently drops, this column starts saying <em>retained</em> without anyone editing this page. "
           (approver-disclosure trail))
      ["Op" "Order" "Decision" "Decided by (audit fact)" "Store register inspected" "Approver in the record?"]
      (rows (map approval-row trail)))

     (section
      "Registry drafts minted by this run"
      (str "The append-only book-of-record drafts <code>agmachtrade.registry</code> actually produced. "
           "Every one is a DRAFT carrying an UNSIGNED certificate — signing is the operator's own legal act, "
           "not this actor's, and no real yard, ERP or billing system was touched.")
      ["Record id" "Kind" "Order" "Jurisdiction" "Mutability"]
      (rows (concat (map draft-row (store/dispatch-history db))
                    (map draft-row (store/invoice-history db)))))

     (section
      (str "Audit ledger (this run — " (count ledger) " facts)")
      "Append-only decision-fact log: every commit, every hold, in the order the actor produced them."
      ["Fact" "Op" "Order" "Basis" "Summary"]
      (rows (map ledger-row ledger)))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>agmachtrade.render-html</code> from a real "
     "<code>agmachtrade.operation</code> → <code>agmachtrade.governor</code> → "
     "<code>agmachtrade.store</code> run driven through <code>langgraph.graph/run*</code>. "
     "Deterministic and timestamp-free: two consecutive builds are byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- build-time invariants -----------------------------

(defn- assert-real-holds!
  "Invariants 1-6. Throws; never warns. A generator that renders a page
  with no HARD hold in it has not demonstrated the thing this repo exists
  to demonstrate, and a page whose HARD-check section outlived the run
  that justified it is a lie with a table around it."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds (hard-holds ledger)
        fired (into #{} (mapcat #(map :rule (:violations %)) holds))
        groups (hard-rule-groups ledger)
        attempts (vec (dispatch-attempts db ledger))]
    (println "LEDGER-FACTS\t" (count ledger))
    (println "HARD-HOLDS\t" (count holds))
    (println "DISTINCT-HARD-RULES\t" (count fired))
    (println "DISPATCH-ATTEMPTS\t" (count attempts))

    ;; 1 -- at least one real HARD hold
    (when (zero? (count holds))
      (throw (ex-info (str "render-html: the run produced ZERO :governor-hold facts -- "
                           "refusing to write a console that shows no HARD hold")
                      {:ledger-facts (count ledger)})))

    ;; 2 -- evidence floor on DISTINCT rules
    (when (< (count fired) min-distinct-hard-rules)
      (throw (ex-info "render-html: fewer distinct HARD rules fired than the evidence floor requires"
                      {:fired (vec (sort-by str fired))
                       :count (count fired)
                       :floor min-distinct-hard-rules})))

    ;; 3 -- a HARD hold must never have asked a human
    (doseq [r runs]
      (let [ts (into #{} (map :t (:audit r)))]
        (when (and (contains? ts :governor-hold) (contains? ts :approval-requested))
          (throw (ex-info "render-html: a run that HARD-held ALSO escalated to a human approver"
                          {:thread (:thread r) :op (:op r) :subject (:subject r)})))))

    ;; 4 -- no rendered HARD-check row without a backing ledger rule
    (doseq [{:keys [rule]} groups]
      (when-not (contains? fired rule)
        (throw (ex-info "render-html: HARD-check row has no backing ledger fact" {:rule rule}))))

    ;; 5 -- the SOFT gate must have shown BOTH of its outcomes. Without this
    ;; floor an empty approval trail renders as a legitimate-looking (but
    ;; empty) table, and the derived attribution sentence degrades to
    ;; "nothing to attribute" -- silence that reads like a pass.
    (let [trail (approval-trail db runs)
          outcomes (frequencies (map :outcome trail))]
      (println "APPROVALS-GRANTED\t" (get outcomes :approval-granted 0))
      (println "APPROVALS-REJECTED\t" (get outcomes :approval-rejected 0))
      (when (zero? (get outcomes :approval-granted 0))
        (throw (ex-info (str "render-html: no approval was GRANTED -- the approval trail and the "
                             "approver-attribution probe would both be vacuous")
                        {:trail (count trail)})))
      (when (zero? (get outcomes :approval-rejected 0))
        (throw (ex-info "render-html: no approval was REJECTED -- the SOFT gate's other outcome was never demonstrated"
                        {:trail (count trail)}))))

    ;; 6 -- the type-gating control pair, stated by machine facts rather
    ;; than by order id so that renaming a demo order cannot retire it.
    (let [no-certs? (fn [o] (and (not (:emissions-certificate? o))
                                 (not (:rops-certified? o))))
          control-b (first (filter #(and (:engine-powered? %) (not (:ride-on? %))
                                         (no-certs? %)
                                         (:emissions-fired? %) (not (:rops-fired? %)))
                                   attempts))
          control-a (first (filter #(and (not (:engine-powered? %)) (not (:ride-on? %))
                                         (no-certs? %) (:dispatched? %))
                                   attempts))]
      (println "TYPE-GATE-CONTROL-A\t" (:id control-a))
      (println "TYPE-GATE-CONTROL-B\t" (:id control-b))
      (when-not control-b
        (throw (ex-info (str "render-html: no dispatch attempt proved the emissions and ROPS gates are "
                             "INDEPENDENT -- need an engine-powered, non-ride-on order carrying neither "
                             "certificate that fires the emissions rule and NOT the ROPS rule")
                        {:attempts (mapv #(select-keys % [:id :engine-powered? :ride-on?
                                                          :emissions-fired? :rops-fired?])
                                         attempts)})))
      (when-not control-a
        (throw (ex-info (str "render-html: no dispatch attempt proved both certificate gates are true "
                             "NO-OPs -- need a non-engine, non-ride-on order carrying neither certificate "
                             "that dispatches anyway")
                        {:attempts (mapv #(select-keys % [:id :engine-powered? :ride-on? :dispatched?])
                                         attempts)}))))

    {:holds (count holds) :rules (vec (sort-by str fired))}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        {:keys [holds rules]} (assert-real-holds! result)
        html (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count (store/ledger (:db result))) " ledger facts, "
                  holds " HARD holds over " (count rules) " distinct rules, "
                  (count (store/dispatch-history (:db result))) " equipment-dispatch drafts, "
                  (count (store/invoice-history (:db result))) " equipment-invoice drafts, "
                  (count html) " bytes)"))
    (println "HARD-RULES\t" (str/join " " (map str rules)))))

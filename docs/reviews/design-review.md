1. What’s strong

Clear central abstraction (FarroEnvelope)

Canonical in-memory representation of a job with explicit fields for ID, role, status, prompt, routing, timestamps, and payloads.

Clean separation between:

fs.clj as “primitive I/O”

job.clj as “job lifecycle + canonicalization”

agentd.clj / managerd.clj as “business logic orchestrating envelopes.” 

architecture_design

Incremental, phased plan (M2 onward)

Phase 1: introduce job.json + job.clj and refactor daemons around the envelope.

Phase 2: watchdog + retries.

Phase 3: auto-plan + backpressure.
This matches how you want to grow FARRO (pay down architecture early, then add resilience and automation).

Engineer-friendly checklists

Concrete bullets for what a Senior Engineer should create/modify: new module, refactor daemons, add tests, etc.

You can hand Phase 1 as a task straight to an Engineer with only minor prompt framing. 

architecture_design

Solid testing strategy

Separate unit tests for job.clj.

Round-trip tests and golden fixtures.

Snapshot testing of the envelope to detect accidental schema drift.

Overall: it hits the “Architect defines clear module boundaries + a crisp data model + a plausible migration plan” bar.

2. Gaps / ambiguities worth tightening

None of these are fatal, but if you fix them now you’ll save back-and-forth with engineers.

Persisted vs runtime fields in FarroEnvelope

Right now the envelope intermixes “on-disk fields” (status, attempt, routing, etc.) with “runtime-only fields” (:lock-handle, :job-path, :config-snapshot) in one map. 

architecture_design

I’d explicitly define two layers:

Persisted envelope (what lives in job.json):
job-id, role, status, attempt, timestamps, routing, allowed paths, etc.

Runtime overlay (never written to disk):
lock-handle, open file descriptors, transient config, etc.

And make it explicit that commit-job! only writes the persisted subset back out. That prevents “oops, we serialized the lock handle”-type bugs.

Error and retry semantics

The doc says “retry with backoff” and “watchdog for stale jobs,” but doesn’t pin down:

When is a job considered permanently failed vs. eligible for requeue?

Which statuses must be terminal (:succeeded, :failed, :killed) and which are transient (:queued, :in-progress, :stale)?

How attempt interacts with max retries (e.g., :failed after N attempts, or :stale then :queued again?).

I’d add a tiny state machine for :status with legal transitions and who is allowed to trigger them (agent vs manager vs CLI).

Locking and idempotency of load-job / commit-job!

The flow is clear (“lock → move → read → snapshot → envelope → work → commit”), but you don’t quite specify:

Is load-job idempotent if a daemon crashes mid-way?

If commit-job! crashes after writing job.json but before moving dirs, what do we do on restart?

How do we distinguish “job truly in progress” vs “orphaned job that needs watchdog intervention”?

I’d add explicit invariants like:

“A job with status :in-progress and last updated-at older than X is considered orphaned and eligible for requeue or kill by the watchdog.”

“load-job must only be called for jobs in :queued status; commit-job! is the only function allowed to change status to :in-progress or terminal states.”

job.json schema and versioning

The design implies job.json becomes the canonical lifecycle store, but doesn’t define its schema or how to version it. 

architecture_design

I’d add:

A minimal JSON schema (even prose): required fields, optional fields, and defaulting rules.

A :schema-version field so you can evolve it later without breaking old jobs.

A note on how prompt.json and job.json merge when both exist.

Backpressure thresholds / auto-plan details

Phase 3 mentions “80% threshold,” but not:

Whether that’s configurable per role/queue.

What happens if multiple managers run concurrently.

Whether auto-plan jobs are tagged in the envelope (e.g., :origin :auto-plan vs :manual-enqueue).

I’d specify:

A configuration key (e.g., :max-queue-depth, :queue-watermark) and where it lives.

A simple strategy to avoid planners stepping on each other (single manager instance vs file-based lock vs “one manager per factory” invariant).

Relationship to FARRO hardened JSON spec

Given you now have a hardened JSON spec (FARRO/FJ), I’d explicitly say:

All envelope fields and job.json must conform to the hardened JSON rules (no arbitrary nested dynamic keys, stable field set, etc.).

Where the FJ validation happens: inside job.clj on load/commit.

This makes it obvious how the two documents relate.
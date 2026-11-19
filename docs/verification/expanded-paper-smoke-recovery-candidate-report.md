# Native recovery candidate verification

This is historical regression evidence, **not release acceptance**.

- Artifact SHA-256: `88c2a632b53b2f553ab114a7df7e42ec4781b721c52d382f2a5f64b39d94191f`.
- Actual server stop exit codes: `0`, `0`; actual client exit codes: `0`, `0`, `0`.
- Fresh isolated Paper 1.21.11 build 132, real audited StarCore, two non-operator loopback clients, real public NationService membership.

## Reproduced failure

Both initial native GUI choices at 17:57:52Z collided with polling and were rejected as busy. Real refresh and one retry at 17:58:56Z / 17:58:57Z advanced the unchanged original sessions. Root coordinator is fixing this race before a new final build.

## Evidence checks

- all snapshots use 88c2 candidate: PASS
- two server stops exit zero: PASS
- actual distinct native branches: PASS
- normal quit reconnect preserves native owned state: PASS
- gui advanced both native sessions to departure: PASS
- offline source recorded without fabricated instance: PASS
- offline restart preserves history and checkpoints: PASS
- both logins recover one original shared instance: PASS
- actual native engine resumes departure: PASS
- actual gui finishes both native sessions: PASS
- native start outcomes do not replay: PASS
- offline shared instance completes with same two members: PASS
- all readonly integrity checks ok: PASS
- original busy race is retained: PASS
- no source collision: PASS

The root coordinator stopped release acceptance to repair the busy race. National invitation, NPC/fate reward and technology/disband paths were not repeated in this shortened run. They require verification against the next frozen artifact. No journal state was reset and no plugin database was written by the fixture.

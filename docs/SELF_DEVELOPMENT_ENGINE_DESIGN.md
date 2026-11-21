# Self-development proposal engine

## Current implementation slice

NOID stores source-change proposals separately from the selected project tree. Each record contains a relative path, a bounded original snapshot, a proposed replacement, a rationale, and SHA-256 fingerprints of both text versions. Records are local Room data and have three states:

- `PENDING_REVIEW`: inert proposal awaiting the user's decision.
- `APPROVED`: the user approved the proposal for a later, separately authorized application step.
- `REJECTED`: the proposal was declined.

The repository validates the relative path using the existing project-file policy, accepts only supported text extensions, caps each text snapshot at 2 MiB, rejects unchanged content, and records the original/proposed fingerprints. Room transitions are conditional on the record still being pending, preventing repeated or conflicting review actions.

## Explicit non-capabilities

This slice does not generate code from the model, inspect project files automatically, write to SAF, execute project code, run shell commands, create a sandbox, or mark a change as applied or verified. Approval is not file-write authorization. Existing project-file writes remain separate user-started operations with an exact preview, SAF grant revalidation, backup, and audit event.

## Required next integration gates

1. Add a review screen for pending and historical proposals.
2. Connect model-generated proposals only through a strict, bounded schema; treat all model output as untrusted text.
3. Re-read the selected SAF file immediately before any apply attempt and compare its current SHA-256 with `originalSha256`. A mismatch must mark the proposal stale and refuse to write.
4. Show the exact path, original/proposed content, bounded diff, and rationale before the user confirms.
5. Apply only through the existing `ToolRegistry.executeUserAction` boundary, re-deriving the exact persisted SAF write grant and writing a pre-execution audit event.
6. Reuse the existing backup-backed `ProjectFileRepository.writeText` path. Do not add shell, arbitrary code execution, or a claimed sandbox.
7. Add tests for stale snapshots, revoked grants, failed pre-audit, cancellation, rollback, and repeated apply attempts before exposing apply in the UI.

The current code is a durable proposal/review foundation, not a completed autonomous self-development engine.

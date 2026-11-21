# Self-development proposal engine

## Implemented proposal and review foundation

NOID stores source-change proposals separately from the selected project tree. Each record contains a normalized relative path, bounded original and proposed snapshots, a rationale, and SHA-256 fingerprints of both text versions. Records are local Room data with three states:

- `PENDING_REVIEW`: inert proposal awaiting the user's decision.
- `APPROVED`: approved for a later, separately authorized application step.
- `REJECTED`: declined proposal.

The repository validates paths and supported text extensions, caps each snapshot at 2 MiB, rejects unchanged/empty proposed content, and records both fingerprints. Conditional DAO transitions prevent repeated review decisions.

The Compose review screen is connected to the Room repository and ViewModel. It shows pending and historical proposals, the exact relative path, rationale, status, both hashes, a bounded line diff, and expandable bounded previews of original/proposed snapshots. Users can approve or reject pending proposals. Approval is explicitly informational and never writes to the selected project. The screen does not receive a SAF URI or write capability.

## Explicit non-capabilities

The current slice does not create model-generated proposals, inspect project files automatically for changes, re-read source snapshots, detect stale proposals, write to SAF, execute project code, run shell commands, create a sandbox, or mark a change as applied or verified. It is a proposal storage and human-review interface, not an autonomous self-development engine.

## Required next integration gates

1. Connect model output to proposal creation only through a strict, bounded schema; treat all model output as untrusted text and bind paths only to the app-owned selected SAF tree.
2. Re-read the exact SAF file immediately before any apply attempt and compare its current SHA-256 with `originalSha256`. A mismatch must mark the proposal stale and refuse to write.
3. Show the exact path, original/proposed content, bounded diff, and rationale before the user confirms.
4. Apply only through the existing `ToolRegistry.executeUserAction` boundary, re-deriving the exact persisted SAF write grant and writing a pre-execution audit event.
5. Reuse the existing backup-backed `ProjectFileRepository.writeText` path. Do not add shell, arbitrary code execution, or a claimed sandbox.
6. Add tests for stale snapshots, revoked grants, failed pre-audit, cancellation, rollback, and repeated apply attempts before exposing apply in the UI.

Approval is not file-write authorization. Existing project-file writes remain separate user-started operations with an exact preview, SAF grant revalidation, backup, and audit event.

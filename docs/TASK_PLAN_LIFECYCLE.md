# NOID AI — Task Plan Lifecycle and Recovery

**Status:** Metadata-only recovery is implemented; automated migration tests pass, including preservation of populated v7 data under Robolectric. Target-device process-death testing remains pending.  
**Scope:** Android task-plan orchestration and its Room-backed recovery metadata.  
**Safety objective:** A process restart or ambiguous tool outcome must never replay an old plan automatically.

## 1. Implemented behavior

The coordinator keeps executable plan sessions and approval tickets in memory. Room database version 8 adds one metadata row per plan in `task_plan_recovery`.

The row contains only:
- Plan ID and step count.
- Current step index and current step ID.
- Plan-level state and a `dispatchStarted` flag.
- Creation and update timestamps.

It does **not** store the plan goal, individual step records, tool arguments, approval-ticket IDs, raw tool outputs, credentials, or file contents. There is no durable child-step table and no per-step `OUTCOME_UNKNOWN` state.

The production ChatViewModel wires the recovery store into the coordinator, loads up to 100 unresolved records, and displays a warning for plans requiring review. Cancellation and conversation clearing cancel live sessions and refresh the recovery state.

## 2. Actual state model

The persisted plan-level states are:
- `RUNNING`
- `WAITING_APPROVAL`
- `COMPLETED`
- `STOPPED`
- `CANCELLED`
- `INTERRUPTED`
- `REVIEW_REQUIRED`

The database stores the current step as an index and optional step ID, not as an independently persisted step-state machine.

### Startup recovery

On the first store access in a process, active rows in `RUNNING` or `WAITING_APPROVAL` are updated:
- If `dispatchStarted = true`, state becomes `REVIEW_REQUIRED`.
- Otherwise, state becomes `INTERRUPTED`.

The recovery update clears `dispatchStarted` and updates the timestamp. The current step metadata remains available for review. The store does not recreate a coordinator session or approval ticket, and no plan is automatically resumed.

`REVIEW_REQUIRED` means a tool may have started before the process ended; its external effect must be checked before the user creates a new attempt. `INTERRUPTED` means the persisted metadata did not indicate an in-flight dispatch. Neither state means the plan completed.

## 3. Dispatch and approval safeguards

1. The plan is validated before execution and a unique recovery row is created.
2. Before invoking a tool, the coordinator persists the current step and sets `dispatchStarted = true`. If this persistence fails, the tool must not run.
3. When a sensitive action pauses for approval, the plan is persisted as `WAITING_APPROVAL`; the approval ticket remains in memory only.
4. A live approval is checked through the tool registry. Before the approved tool is invoked, dispatch metadata is persisted.
5. After a step succeeds and its completion audit is recorded, the store advances the current index and clears the dispatch flag.
6. A terminal state is persisted for completion, stop, or cancellation. Persistence failures fail closed rather than claiming a successful transition.
7. Recovery never retries tools and never restores approval tickets.

A database write and an arbitrary external side effect cannot be made one atomic transaction. Therefore `REVIEW_REQUIRED` is deliberately conservative; it does not claim exactly-once execution.

## 4. Migration

Room migration 7→8 creates `task_plan_recovery` and leaves existing tables untouched. The migration contract test verifies the migration version pair, table-creation SQL, expected metadata columns, and absence of payload/credential columns.

A populated-v7 integration test now creates a version-7 Room fixture, inserts existing rows into chat, memory, learning, proposal, and audit tables, then opens the same database with the production v8 database and the actual 7→8 migration. It verifies all five rows remain readable and the new recovery table is created empty. The test runs under Robolectric SDK 35; this is an automated JVM test, not a physical-device migration test. Room schema export remains disabled.

## 5. User-facing recovery

The app shows an unresolved-plan warning with plan ID, current step information, and persisted state. It advises the user to inspect the audit history before attempting the action again.

Current UI is a warning/review entry point, not a complete recovery-history workflow. It does not yet provide a full per-step timeline, archive/dismiss lifecycle, or an automated reconciliation process. Raw tool arguments and outputs are not exposed by the recovery record.

## 6. Automated verification already present

- Store tests cover recovery of active and approval-waiting records, duplicate plan IDs, terminal-state stability, and dispatch-state constraints.
- Coordinator integration tests cover successful completion persistence and rejection of a stale approval after coordinator recreation.
- Migration contract test covers the 7→8 table-creation contract and metadata-only schema.
- Populated migration integration test verifies preservation of existing rows in all five pre-existing tables and creation of the empty recovery table.
- Room KSP is configured for test sources so the v7 fixture receives its generated implementation.
- CI run **45040148794** completed successfully, including Android unit tests and the populated migration test. The workflow does not package an APK.

These tests do not prove Android process-death behavior on the target phone, nor do they verify migration of a populated v7 database.


- Coordinator failure-path integration tests also verify that a tool exception after dispatch moves the plan to `REVIEW_REQUIRED`, rather than recording a misleading `STOPPED` state. A normal stop before any dispatch may be recorded as `STOPPED`; after dispatch begins, the state remains conservative and requires review.
- The unexpected persistence-failure test verifies immediate `REVIEW_REQUIRED` marking when recording a successful step fails after tool execution.
- CI run **45041155074** completed successfully for all seven jobs, including Android unit tests. The workflow remains test-only and does not package an APK.

## 7. Remaining acceptance work

1. Manually test on the target Android device: start a plan, interrupt/force-stop the app at safe points before and after dispatch, reopen it, and verify it is marked `INTERRUPTED` or `REVIEW_REQUIRED` as appropriate, with no automatic replay.
2. Verify the warning banner and audit-review instructions on the device.
3. Keep CI test-only; do not package an APK before the project is complete and the user approves it.

## 8. Explicit non-goals

This recovery feature does not implement model-weight training, unrestricted self-modification, automatic retry of side-effecting tools, background execution, voice features, or privileged device control. Recovery metadata is operational state, not a learning memory and not evidence that the model improved.

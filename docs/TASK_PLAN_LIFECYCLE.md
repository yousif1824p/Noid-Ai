# NOID AI — Task Plan Lifecycle and Recovery Specification

**Status:** Engineering specification; implementation pending  
**Scope:** Android task-plan orchestration and its Room-backed recovery record  
**Safety objective:** Process death, app restart, cancellation, or an ambiguous tool outcome must never cause an old plan to execute again without a fresh user decision.

## 1. Current implementation boundary

The current `AssistantTaskPlanCoordinator` keeps sessions in an in-memory map. A session contains the plan, completed results, next step index, and (when paused) the current approval-ticket ID. `ToolRegistry` approval tickets are also runtime objects. The chat view model cancels sessions when generation is cancelled or the conversation is cleared.

Consequences:

- An app-process restart destroys active sessions and runtime approval tickets.
- The current implementation does not automatically resume a plan after restart.
- It also does not yet provide a durable, user-visible record explaining which plan was interrupted.
- No approval ticket, tool argument, raw tool output, or credential may be persisted as a recovery shortcut.

This document defines the next safe increment. It does not claim that durable recovery is already implemented.

## 2. Non-negotiable recovery rules

1. **Never auto-resume.** A plan found active after process restart becomes `INTERRUPTED`; it is not executable.
2. **Never restore an approval ticket.** Runtime ticket IDs are ephemeral and must not be stored as reusable authorization.
3. **Fresh approval is mandatory.** Any later attempt to continue must be reconstructed as a new plan and pass normal validation and the existing approval gate.
4. **No blind retries after ambiguous dispatch.** If the process dies after a tool may have started but before its outcome is durably recorded, mark that step `OUTCOME_UNKNOWN`. Do not replay it automatically.
5. **Persist metadata, not payloads.** Recovery records contain identifiers, bounded goal text, counts, timestamps, status, and safe failure codes only. Do not store tool arguments, file contents, secrets, or raw outputs.
6. **Fail closed.** If the recovery store cannot be updated at a required transition, stop dispatching further steps.
7. **Do not imply exactly-once execution.** A local database transaction cannot make an arbitrary external side effect atomic with the tool call. Ambiguous outcomes require reconciliation, not a retry claim.

## 3. Durable state model

Plan states:

| State | Meaning | May dispatch another step? |
|---|---|---:|
| `CREATED` | Validated plan metadata recorded, no step dispatched | No |
| `RUNNING` | A step is being processed | Only by the live coordinator |
| `WAITING_APPROVAL` | A sensitive step is paused for the current process | No, until fresh in-process approval |
| `COMPLETED` | Every step completed and its completion audit succeeded | No |
| `STOPPED` | A step failed, was rejected, or audit was unavailable | No |
| `CANCELLED` | User explicitly cancelled the plan | No |
| `INTERRUPTED` | Process ended before a terminal state was recorded | No |
| `REVIEW_REQUIRED` | At least one step may have executed but its final outcome is unknown | No |

Step states:

- `PENDING`
- `DISPATCHING`
- `AWAITING_APPROVAL`
- `SUCCEEDED`
- `FAILED`
- `REJECTED`
- `CANCELLED`
- `OUTCOME_UNKNOWN`

A step must be durably marked `DISPATCHING` before invoking its adapter. If restart recovery finds `DISPATCHING`, it changes that step to `OUTCOME_UNKNOWN` and the plan to `REVIEW_REQUIRED`. This conservative rule intentionally prefers a possible incomplete task over a duplicate destructive action.

## 4. Transition contract

- `start`: validate the entire plan first; create a durable record before any tool dispatch.
- Before each dispatch: persist the step index and `DISPATCHING`; if persistence fails, do not call the tool.
- Safe completion: persist `SUCCEEDED` and the next index before proceeding.
- Sensitive action: persist `AWAITING_APPROVAL`; keep the approval ticket only in memory.
- Approval: validate the live ticket through `ToolRegistry`; persist `DISPATCHING` before invocation. A stale, missing, or mismatched ticket is rejected.
- Rejection/cancellation: revoke the live ticket, persist a terminal state, and prevent later steps.
- Completion: persist `COMPLETED` only after every step has a successful, completion-audited result.
- Restart: transactionally convert non-terminal plans to `INTERRUPTED`, except plans with a `DISPATCHING` step, which become `REVIEW_REQUIRED` with that step `OUTCOME_UNKNOWN`.

## 5. Proposed storage contract

Introduce a Room entity for plan recovery metadata and a child step entity, or a single bounded row if schema simplicity is preferred. The chosen representation must enforce:

- Stable, validated plan ID with a unique index.
- Maximum 8 steps, matching `AssistantTaskPlanPolicy.MAX_STEPS`.
- Bounded goal and step identifiers.
- Enumerated state values; unknown values fail closed.
- Monotonic update timestamps and explicit created/updated times.
- A transaction for plan-state and step-state transitions.
- No raw proposal arguments, approval ticket IDs, tool output, credentials, or file content.
- Retention limits for terminal records; never delete unresolved `REVIEW_REQUIRED` records automatically.

The database migration must preserve all existing records. Add migration tests from the immediately previous schema and verify the generated Room schema matches the entity definitions.

## 6. User-facing recovery behavior

On opening the task-plan history:

- Show interrupted plans with a clear label and last known step.
- For `OUTCOME_UNKNOWN`, explain that the action may have happened and must be checked before another attempt.
- Offer only safe actions: dismiss/archive after review, inspect the relevant audit events, or create a new plan after the user confirms the current state.
- Do not expose raw tool arguments or sensitive output in the recovery card.
- Never present an interrupted plan as completed.

## 7. Required tests before enabling persistence

1. A process-recovery simulation converts `RUNNING` to `INTERRUPTED`.
2. A `DISPATCHING` step becomes `OUTCOME_UNKNOWN` and is never invoked by recovery.
3. A waiting approval becomes interrupted; the old ticket cannot authorize execution.
4. A fresh plan still requires the normal approval gate for sensitive actions.
5. Failure to persist `DISPATCHING` prevents the adapter from running.
6. Failure to persist completion prevents the next step from running.
7. Repeated recovery is idempotent.
8. Concurrent approval attempts invoke the approved step at most once within the live process.
9. Invalid or unknown persisted states fail closed.
10. Migration preserves existing chat, memory, learning, proposal, and audit records.

## 8. Acceptance criteria

This increment is complete only when:

- Room migration and DAO tests pass.
- Coordinator tests cover cancellation, stale approvals, restart recovery, and ambiguous outcomes.
- Android unit tests pass in CI.
- No APK is packaged as part of CI.
- Documentation and UI accurately distinguish `INTERRUPTED` from `COMPLETED`.
- A manual device test confirms restart behavior; CI alone does not prove Android process-death behavior on the target phone.

## 9. Explicit non-goals

This work does not implement model-weight training, unrestricted self-modification, automatic retry of side-effecting tools, background execution, voice features, or privileged device control. Recovery metadata is operational state, not a learning memory and not evidence that the model improved.

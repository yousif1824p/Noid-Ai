# NOID AI tool-call integration boundary

## Implemented

- The Android registry exposes registered tool schemas to LiteRT-LM as ToolProvider instances.
- The project directory adapter exposes `list_project_directory`; the project-file adapter exposes `read_project_file`. Both accept relative paths only and are restricted to the current user-selected SAF tree.
- Both adapters inject the current user-selected SAF selection token inside the app. The model cannot provide a URI, grant, permission, or selection token.
- `ToolRegistry.requestApprovalFromModel` accepts only known function names and bounded string-only arguments, maps them through the registered adapter, validates the resulting request, and creates a pending approval ticket.
- Creating a ticket does not approve or execute anything. Execution remains behind the explicit UI approval button, current persisted SAF read grant, request revalidation, and the pre-execution audit event.
- Model schemas are represented by LiteRT-LM `OpenApiTool` providers whose `execute` method deliberately throws. This guards against accidentally enabling automatic tool execution.
- The inference adapter creates a conversation with `automaticToolCalling = false`, consumes structured `Message.toolCalls`, and waits for the explicit approval result.
- After approval or rejection, a `Content.ToolResponse` is sent to the same live conversation. The model can then produce its final natural-language response.
- Only one tool call is accepted per model message. A batch of multiple calls is rejected as a whole; no member of that batch is executed.
- A model turn is limited to four sequential tool rounds. Each approval wait is bounded to 115 seconds.
- The ViewModel claims a model response handoff before running an approved tool, preventing a late completion from appearing as an unrelated assistant message after the model turn ends.
- Rejection completes the waiting model call with a bounded failure response. Generation timeout/cancellation cancels outstanding approval tickets and completes their waiting calls so no coroutine remains blocked.
- Unit tests cover handoff registration, single-claim approval, rejection, timeout cleanup, cancellation cleanup, and duplicate approval IDs.
- Unknown tools, malformed arguments, missing project selection, rejected requests, and expired approvals fail closed and are returned to the model as tool errors.

## Mandatory LiteRT-LM configuration

Tool-enabled conversations must use `ConversationConfig(tools = registry.modelToolProviders(), automaticToolCalling = false)`. Never use the default automatic mode for tools.

With manual calling disabled, LiteRT-LM returns structured `Message.toolCalls`. Each call is treated as an untrusted proposal, not as an instruction to execute.

## Current tool scope

The selected project-file read adapter is the only model-exposed tool. It is read-only and can access only supported text files within the current user-selected SAF tree. No shell, arbitrary code, hidden execution, cross-app private data access, or permission bypass is provided.

## Model compatibility

LiteRT-LM tool calling requires a model that supports function calling (for example, FunctionGemma). Importing an arbitrary `.litertlm` model does not guarantee tool-call support. Unsupported models may return ordinary text and never produce structured tool calls.

## Verification boundary

CI unit tests verify Kotlin compilation and unit-test behavior, including proposal validation and response serialization. They do not prove runtime model compatibility, native-library loading, or end-to-end behavior on a physical Android device. Those checks remain required before declaring this feature device-ready.

No intermediate APK packaging is permitted.

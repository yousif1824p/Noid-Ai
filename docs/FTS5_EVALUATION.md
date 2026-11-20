# FTS5 retrieval and Android availability evaluation

## Current implementation

- The app uses Room 2.8.5 and the platform-backed `Room.databaseBuilder` path.
- Conversation search currently uses a bounded, case-insensitive substring query: `instr(lower(text), lower(:query)) > 0`.
- The app supports Android API 26 and newer.

## Findings

1. FTS5 is an optional SQLite virtual-table module, not a capability that can be assumed from the Android API level alone. SQLite's own documentation says it must be enabled in the SQLite build (or loaded as an extension): https://www.sqlite.org/fts5.html
2. Android's platform SQLite build does not compile FTS5 by default. The AndroidX support change introducing Room FTS5 support explicitly notes that FTS5 is available through a bundled SQLite implementation, such as `BundledSQLiteDriver`, rather than the platform SQLite build: https://android.googlesource.com/platform/frameworks/support/+/835b2a85be93812f792a3ac2e9860a6f83c7174e
3. The current app is on Room 2.8.5 and does not configure a bundled SQLite driver. The documented Room `@Fts5` API belongs to Room 3.0.0 and warns that availability depends on the selected driver: https://developer.android.com/reference/androidx/room3/Fts5

## Decision

Do not replace the current search with FTS5 in this stage. There is no verified FTS5 runtime in the current dependency/driver configuration, and changing the database driver or Room major version would add migration, native packaging, and compatibility risk before the app has device validation.

Keep the existing bounded substring search as the reliable fallback. Evaluate FTS5 separately only after an isolated Room 3 + bundled SQLite prototype proves:

- database creation, migration, and index rebuild from existing chat rows;
- supported native ABIs and operation across the app's Android API range (26 through current target);
- Arabic and Iraqi Arabic tokenization, diacritics, punctuation, and mixed Arabic/Latin terms against a held-out corpus;
- safe query construction and predictable behavior for quotes, operators, and empty input;
- insert/delete consistency, index size, query latency, and memory use against the current query;
- graceful fallback if the bundled native driver cannot initialize.

## Scope and status

This is a dependency/runtime evaluation, not an implementation or a claim that FTS5 runs on the target phone. No database schema, search behavior, or native dependency was changed by this evaluation.

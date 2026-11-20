# Controlled dynamic assessment lab fixture

`ControlledLabAssessmentRunner` is a deterministic, side-effect-free simulation fixture for testing NOID's dynamic-assessment authorization and finding pipeline.

It accepts only the fixed fixture identifier `noid.sample-vulnerable-app.simulation.v1` and two bounded probes:
- external redirect allow-list behavior;
- path traversal input rejection.

The fixture never launches an APK or Android component, reads or writes files, starts a process, or sends network traffic. A result with zero side effects is expected by design. Every run result and finding exposes the explicit `simulationOnly=true` marker, including rejected runs. The marker is a derived read-only property rather than a constructor field, so callers cannot set it to false and Kotlin data-class `copy()` cannot remove the provenance label. Regression tests cover this invariant.

The simulated findings validate the policy and reporting flow only; they are not evidence that a real application is vulnerable.

A real deliberately vulnerable sample APK and on-device execution remain required before the roadmap's controlled dynamic-testing item can be completed. Keep the check unavailable in production UI until that integration, explicit per-test confirmation, stop behavior, and target-device validation exist.

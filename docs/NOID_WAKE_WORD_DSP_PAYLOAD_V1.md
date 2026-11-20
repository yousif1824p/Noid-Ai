# NOID wake-word DSP payload v1 — stage contract

Status: design contract for the pinned Heed reference. This document narrows the five existing section labels into exact tensor shapes and packing rules. It does not claim that production coefficients have been generated or that Kotlin parity has passed.

## Fixed geometry

The contract is for the pinned Heed reference geometry only:
- sample rate: 16,000 Hz
- FFT: 512
- Hann: 400 samples, periodic
- hop: 160 samples
- mel bins: 40
- one-sided FFT bins: 257
- centered frames per one-second window: 101

A different geometry requires a new profile and explicit compatibility review; it must not be silently accepted as v1.

## Canonical section order and shapes

All sections are row-major IEEE-754 float32 little-endian. The descriptor's `shape` must equal the exact shape below, not merely multiply to `element_count`.

| Order | Section | Shape | Elements | Meaning |
|---:|---|---:|---:|---|
| 1 | `highpass_sos` | `[4, 7]` | 28 | Four SOS rows |
| 2 | `notch_50hz_sos` | `[1, 7]` | 7 | One SOS row |
| 3 | `notch_60hz_sos` | `[1, 7]` | 7 | One SOS row |
| 4 | `hann_window` | `[400]` | 400 | Periodic Hann coefficients |
| 5 | `mel_filterbank` | `[40, 257]` | 10,280 | Dense mel matrix, including explicit zero weights |

Total: 10,722 float32 values, or 42,888 payload bytes. The existing 1 MiB payload cap is sufficient.

## SOS row packing

Each SOS row is exactly seven float32 values in this order:

`[b0, b1, b2, a1, a2, zi0, zi1]`

- `a0` is normalized to 1 and is not serialized.
- `b0,b1,b2,a1,a2` are the normalized SOS coefficients emitted by the pinned upstream generator.
- `zi0,zi1` are the corresponding two initial state values emitted by that generator.
- Row order is the upstream SOS row order.
- The four high-pass rows are followed by the one-row 50 Hz and one-row 60 Hz sections in the section order above.
- State is initialized for a new stream using the upstream rule `sosfilt_zi(sos) * first_input_sample_of_that_stage`; it is retained across chunks and reset only when a new independent stream begins. The serialized `zi0,zi1` are base state values, not state for an arbitrary user's audio stream; runtime scaling by the first stage input is still required.

The exporter must assert row counts (4, 1, 1), finite values, and coefficient/state order. It must not infer row counts from untrusted descriptor shapes.

## Hann and mel packing

- `hann_window` contains exactly 400 values from `torch.hann_window(400, periodic=True)` at the pinned reference environment.
- `mel_filterbank` is dense row-major `[40,257]`. All 10,280 values are serialized, including zeros. This deliberately avoids the upstream JavaScript sparse-pair representation because v1 has no index stream.
- For mel row `m` and FFT bin `k`, its flat element index is `m * 257 + k`.

## Importer validation requirements

The v1 parser must reject any stage whose shape differs from the fixed shape table, even if element count and hashes are self-consistent. It must also reject a descriptor with a different fixed geometry, a missing/extra/reordered stage, or any payload length other than 42,888 bytes.

Hash checks and finite-float checks remain mandatory. These checks establish container integrity and expected tensor geometry only; they do not prove coefficients are numerically correct.

## Required tests before production generation

1. Valid exact-shape payload accepted.
2. Each stage independently rejected for wrong shape with an otherwise valid descriptor, hashes, and byte ranges.
3. Reordered SOS rows rejected by generator-side golden-vector tests (the importer cannot infer semantic row order from values alone).
4. Dense mel indexing verified with a known matrix containing non-zero values at distinct row/bin coordinates.
5. SOS row packing round-trips coefficients and `zi` values exactly.
6. Whole-window and chunked streaming outputs match the pinned upstream reference within declared tolerances.
7. Two clean generation runs produce identical payload and descriptor hashes under a fully locked environment.

## Non-claims

This is a serialization contract, not an implementation. No production artifact, generator, Kotlin DSP decoder, golden vector, parity proof, real Arabic wake-word model, or target-device measurement is supplied by this document.
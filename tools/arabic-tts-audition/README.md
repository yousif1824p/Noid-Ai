# Arabic TTS audition scorecard validator

This utility validates anonymous CSV ratings and reports per-candidate/per-dialect medians. It uses only the Python standard library.

## Run

From this directory:

```sh
python -m unittest discover -s tests -v
cp examples/ratings-template.csv ratings.csv
# Fill one row per listener, candidate, and dialect after a real listening session.
python validate_scorecard.py ratings.csv --output summary.json
```

The template contains headers only. It is intentionally not valid input until real ratings have been added. Never replace real listening data with invented or example scores.

## CSV format

Required columns, in any order:

`candidate_id,dialect,listener_code,arabic_authenticity,iraqi_fidelity,intelligibility,gender_ambiguity,synthetic_identity,stability`

- `dialect`: `msa` or `iraqi`; score the two dialects separately.
- Use anonymous random listener codes (for example, `L01`), not names, email addresses, or account identifiers.
- Every score is an integer from 1 to 5.
- A listener may rate a given candidate once per dialect in this compact summary format.
- Three or more listeners are flagged as the minimum panel threshold; smaller panels remain valid input but are marked insufficient.
- The tool reports medians and always emits `MANUAL_REVIEW_REQUIRED`. It never approves, ranks, or selects a voice. Malformed quoted CSV rows, invalid UTF-8, duplicate headers, and rows with an incorrect field count are rejected.
- Keep recordings, speaker identities, private notes, and raw personal data out of this public repository. Store any real ratings locally and share only an appropriately anonymized summary when necessary.

This is a clerical validation aid, not an acoustic quality test or proof of gender neutrality. A scorecard cannot replace listening to the generated Arabic samples or target-device testing.

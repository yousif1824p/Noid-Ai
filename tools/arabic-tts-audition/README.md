# Arabic TTS audition scorecard validator

This utility validates anonymous CSV ratings and reports per-candidate/per-dialect medians. It uses only the Python standard library.

## Run

```sh
python -m unittest discover -s tests -v
python validate_scorecard.py ratings.csv --output summary.json
```

## CSV format

Required columns, in any order:

`candidate_id,dialect,listener_code,arabic_authenticity,iraqi_fidelity,intelligibility,gender_ambiguity,synthetic_identity,stability`

- `dialect`: `msa` or `iraqi`; score the two dialects separately.
- Use anonymous random listener codes (for example, `L01`), not names, email addresses, or account identifiers.
- Every score is an integer from 1 to 5.
- A listener may rate a given candidate once per dialect in this compact summary format.
- Three or more listeners are flagged as the minimum panel threshold; smaller panels remain valid input but are marked insufficient.
- The tool reports medians and always emits `MANUAL_REVIEW_REQUIRED`. It never approves, ranks, or selects a voice.
- Do not commit recordings, speaker identities, private notes, or raw personal data to this public repository.

This is a clerical validation aid, not an acoustic quality test or proof of gender neutrality.

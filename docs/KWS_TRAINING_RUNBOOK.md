# Iraqi Arabic wake-word training runbook

Status: preparation only. No «يا NOID» acoustic model is trained, approved, or included in this repository.

## Goal and non-negotiable gates

Train and evaluate a local detector for the exact spoken phrase «يا NOID». A successful training command is not evidence of a usable detector. The model stays disconnected from Android and microphone capture stays disabled until the final held-out evaluation and target-phone runtime gates pass.

- Use a computer-based, private workspace. Do not use Termux, Colab, hosted trainers, or third-party upload services for participant recordings.
- Keep raw recordings, participant identities, consent records, checkpoints, and exported model files outside Git and outside shared/cloud-synced folders.
- Record explicit consent for this specific wake-word evaluation before recording. A speaker can withdraw; remove their recordings and derived artifacts from the private workspace when requested.
- Use separate speakers for `train`, `calibration`, and `final_test`. Never place clips from one speaker in multiple splits.
- Keep the final test split sealed until training choices and the trigger threshold are frozen.
- The repository validator checks dataset structure and minimum quantities; it does not assess audio quality, consent validity, model quality, or detection accuracy.

## 1. Prepare the private workspace

Create a private directory on the training computer, for example:

```text
noid-private-kws/
  dataset/
    manifest.csv
    positive/
    negative/
  heed-project/
  runs/
  exports/
  consent/                 # separate, access-restricted records
```

Do not put this directory under the Git checkout. Restrict filesystem access to the people conducting the evaluation. Backups, if needed, must be encrypted and private.

The dataset manifest must use the validator's exact columns:

```csv
speaker_id,split,label,consent,path
speaker-001,train,positive,yes,positive/clip-001.wav
speaker-002,final_test,negative,yes,negative/room-001.wav
```

Use non-identifying random speaker codes in the manifest. Keep the mapping to real identities, if one is required for consent administration, in a separate protected record. Use WAV audio and unique relative paths.

## 2. Collect representative recordings

The project's minimum structural gate is:

- At least 300 positive utterances of «يا NOID» from at least 10 consenting speakers.
- At least 4 positive speakers held out entirely in `final_test`.
- At least 8 hours of varied negative audio overall, including at least 2 hours from at least 2 held-out speakers.
- Every row has explicit consent, and no speaker appears in more than one split.

Capture natural variation: near/far microphone distance, quiet and normal volume, different speaking speeds and intonation, and different rooms. Negative audio should include ordinary Iraqi Arabic conversation, silence, television/music, everyday speech, and carefully chosen phonetic near-misses. Do not make the negative set artificially easy.

The 300-utterance threshold is this project's conservative evaluation requirement; it is intentionally stricter than the small starter set described by the Heed project. Do not lower it merely because a training tool can produce a model from fewer clips.

## 3. Validate before training

Run from the repository root on the computer:

```sh
python tools/kws-dataset/validate_dataset.py /absolute/private/path/noid-private-kws/dataset
```

Interpret the exit code exactly:

- `0`: structural and quantity gates passed.
- `1`: readable dataset, but one or more minimum quantity gates are not met.
- `2`: invalid manifest or audio paths/files; correct the errors before proceeding.

Keep the validator output with the private experiment notes. Never commit the real manifest or audio. CI uses synthetic fixtures only.

## 4. Train an experimental Heed model locally

Heed is the current prototype candidate because its upstream project documents custom phrase training, local CLI/studio workflows, ONNX/TFLite export, and a preprocessing contract. This does not establish Arabic/Iraqi accuracy or Android readiness.

On a dedicated computer environment (not the phone), install the upstream package and inspect the installed CLI before running a training job:

```sh
python -m venv .venv
# Activate .venv using the operating system's instructions.
python -m pip install --upgrade pip
python -m pip install "heed-wakeword[export]"
python -m heed.cli --help
heed doctor
heed init ya_noid --phrase "يا NOID"
heed train ya_noid --model-size medium
heed export ya_noid
```

### Important: import the CSV dataset into Heed (manual, local-only)

The NOID CSV manifest is **not** a Heed training input. The upstream CLI source confirms that `heed init` creates a project containing `config.json`, `positive/`, and `negative/`; the trainer then loads WAV files from those two directories. It does not parse NOID's `speaker_id` or `split` columns.

Use this explicit staging procedure on the private computer:

1. Create the project in the private workspace, not in the Git checkout:
   ```sh
   heed init /absolute/private/path/noid-private-kws/heed-project/ya_noid --phrase "يا NOID"
   ```
2. Open `manifest.csv` and copy **only rows whose split is `train`**. Copy each referenced WAV into the Heed project's `positive/` or `negative/` directory according to its `label`. Give the copies neutral sequential filenames; do not include participant names or speaker IDs in filenames.
3. Do not copy `calibration` or `final_test` audio into either Heed training directory. Keep those files in the original private dataset, in separate label-specific staging directories for evaluation. Keep `final_test` sealed until the model and threshold are frozen.
4. Confirm that both Heed directories contain only the intended training-split WAV files before running `heed train`. The trainer performs its own random clip-level validation split inside the imported training data; because only the `train` speaker group is imported, this internal split cannot pull calibration/final-test speakers into training.
5. After training, use only the calibration staging directories to select a threshold. Heed's `heed eval` accepts `--positive-dir`, `--negative-dir`, and `--threshold`; it reports per-clip TPR/FPR. It does **not** calculate false activations per hour or a speaker-grouped report, so use the separate local continuous-event evaluator at `tools/kws-dataset/evaluate_continuous.py` with timestamped detector output and ground-truth phrase intervals. It reports false activations per negative-audio hour and recall, but does not run the detector or inspect waveforms. Do not treat Heed's clip-level FPR as the required <=0.1 false activations/hour result.
6. Freeze and hash the model and chosen threshold. Only then evaluate the sealed `final_test` directories once, and preserve the complete per-speaker/per-condition report.

NOID now provides a local CSV-to-Heed staging utility at `tools/kws-dataset/import_dataset_to_heed.py`. It validates the full dataset before copying only train rows into a fresh Heed project. Never use a script that copies every manifest row into Heed's training directories: that would leak held-out speakers into training. Never commit the manifest, staged audio, or model files.

The commands above are a starting experiment, not a guaranteed Arabic recipe. Confirm the installed version and each command's `--help` output first. If the installed release cannot accept, tokenize, synthesize, or correctly evaluate the Arabic phrase, stop and document that limitation; do not silently substitute an English phrase or label an English model as Arabic.

Heed documents optional multi-speaker TTS augmentation. Use it only after confirming that the selected TTS voice actually pronounces Iraqi/Arabic «يا NOID» correctly. Synthetic samples supplement genuine consented speaker data; they do not count toward the project's 300 real positive utterances or held-out real-speaker requirement. Do not use a hosted notebook or upload recordings to obtain synthetic samples.

Keep each experiment in a new private run directory. Record package versions, source revision, command line, model size, random seed if exposed, dataset-manifest hash, and all configuration changes. Do not overwrite the only copy of a previous model.

## 5. Evaluate without contaminating the final test set

1. Train only with the training split.
2. Use calibration speakers/audio to choose the trigger threshold and any smoothing/refractory settings.
3. Freeze the model and configuration; record their SHA-256 hashes.
4. Only then evaluate once against the sealed final-test split.
5. Report per-speaker and per-condition results, not only aggregate scores.
6. If the model fails, return to development using training/calibration data. Create a new frozen candidate before opening the final test set again; preserve the prior report and document the test-set exposure.

Acceptance targets (not current results):

| Metric | Required target |
|---|---:|
| Recall on held-out real speakers | >= 95% |
| False activations | <= 0.1 per hour |
| Median latency after phrase end | <= 300 ms |
| 95th-percentile latency | <= 500 ms |
| Crashes/unrecoverable stream errors | 0 during 30 minutes |
| Audio sent over network during inference | 0 bytes |

Do not infer false-activation rate from a few hand-picked negative clips. Measure it over the required negative hours and report the observation duration and count.

## 6. Export and preserve the candidate

Keep the exported ONNX/TFLite file and its preprocessing contract together. Record:

- SHA-256 for every artifact.
- Heed package version and source/license.
- Format, input sample rate, frame size, tensor shape, operators, and preprocessing configuration from the exported contract.
- Training-data manifest hash (not the manifest itself), split policy, training configuration, and evaluation report.
- Known limitations and exact device/runtime versions used for testing.

Never commit model weights, recordings, private manifests, consent records, or speaker identifiers to Git. Do not integrate the artifact into Android just because export succeeds.

## 7. Android integration remains blocked

Before connecting a candidate to `KeywordSpottingRuntime`, separately verify on the actual target phone:

- Native runtime and every required operator work on arm64 with no network.
- Kotlin preprocessing matches the exported Heed preprocessing contract using reference vectors.
- Held-out accuracy and false-activation thresholds remain satisfied.
- Memory, CPU, latency, thermal behavior, battery drain, and a 30-minute stability run pass.
- Only after those checks, implement the explicit user-started microphone foreground service, visible notification, runtime permission flow, immediate stop, and Android 14+ compliance.

No background/boot microphone start, no silent restart, and no raw-audio persistence by default. APK packaging remains prohibited until all project work is complete and separately approved.

## Upstream references

- Heed repository and CLI/training/export documentation: https://github.com/AndreiBulzan/heed-wakeword
- Heed local studio workflow: https://github.com/AndreiBulzan/heed-wakeword/blob/main/docs/studio.md
- Project-specific dataset gates: `tools/kws-dataset/README.md`
- Project-specific evaluation gates: `docs/VOICE_MODEL_VALIDATION_PLAN.md`

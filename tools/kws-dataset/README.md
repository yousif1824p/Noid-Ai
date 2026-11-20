# Private KWS dataset validator, Heed importer, and continuous evaluator

These standard-library-only tools operate on private local datasets and CSV event exports. They do not upload, transcode, or commit recordings.

## Validate and stage training data

```sh
python tools/kws-dataset/validate_dataset.py /private/path/to/dataset
heed init /absolute/private/path/noid-heed/ya_noid --phrase "يا NOID"
python tools/kws-dataset/import_dataset_to_heed.py /private/path/to/dataset /absolute/private/path/noid-heed/ya_noid
```

The importer validates structural and quantity gates, then copies only `train` rows into an empty Heed project. Calibration and final-test audio are excluded. Keep recordings and consent records outside Git.

## Continuous-audio evaluation

The evaluator measures timestamped detections exported by a separately run local detector. It does not open audio or execute a model.

Create three private CSV files:

`recordings.csv`:
```csv
recording_id,speaker_id,split,label,condition,duration_seconds
rec-001,spk-a,final_test,positive,quiet,30
rec-002,spk-b,final_test,negative,television,3600
```

`detections.csv`:
```csv
recording_id,timestamp_seconds,confidence
rec-001,12.45,0.97
rec-002,725.2,0.88
```

`ground_truth.csv` (phrase intervals in seconds from recording start):
```csv
recording_id,start_seconds,end_seconds
rec-001,10.1,12.0
```

Run after freezing the model and confidence threshold:
```sh
python tools/kws-dataset/evaluate_continuous.py /private/path/recordings.csv /private/path/detections.csv /private/path/ground_truth.csv --threshold 0.85 --tolerance-seconds 0.5 --split final_test
```

The input recordings CSV must contain exactly one split. Use `--split calibration` to assess calibration data or `--split final_test` for the sealed final evaluation; mixed-split inputs are rejected. The report includes phrase recall, missed phrases, false activations per hour of negative audio, unmatched triggers on positive recordings, and a speaker/condition breakdown. Detections below the threshold are ignored. A trigger is matched at most once to a phrase interval when its timestamp falls within that interval plus/minus the tolerance.

Limitations:
- The evaluator trusts supplied durations, ground-truth intervals, detector timestamps, confidence values, and split labels. Validate them independently.
- It does not inspect waveforms, calculate latency from acoustic phrase end, prove model identity, or enforce that final-test data remained sealed.
- Use calibration recordings to choose the threshold. Freeze/hash the model and threshold before final-test evaluation; never tune against final-test results.
- False-activation/hour uses negative-audio duration only. Keep the negative set representative.
- CI uses synthetic CSV fixtures only. Never commit recordings, manifests, event exports, speaker mappings, consent records, model weights, or voiceprints.

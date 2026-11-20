import csv
from pathlib import Path
import unittest

import numpy as np
from scipy.signal import sosfilt

from generate_dsp_v1 import build_sections


FIXTURE = (
    Path(__file__).resolve().parents[3]
    / "app/src/test/resources/wakeword-sos-parity-v1.csv"
)


def read_fixture():
    stages = {"highpass": [], "notch50": [], "notch60": []}
    values = {}
    names = {
        "sos_highpass": "highpass",
        "sos_notch50": "notch50",
        "sos_notch60": "notch60",
    }
    with FIXTURE.open(newline="", encoding="utf-8") as handle:
        for record in csv.reader(line for line in handle if not line.startswith("#")):
            if not record:
                continue
            if record[0] in names:
                stages[names[record[0]]].append(
                    np.asarray(record[1:], dtype=np.float32)
                )
            else:
                values[record[0]] = np.asarray(record[1:], dtype=np.float32)
    return {name: np.asarray(rows, dtype=np.float32) for name, rows in stages.items()}, values


class SosParityVectorTest(unittest.TestCase):
    def test_checked_in_coefficients_match_pinned_heed_generator(self):
        fixture_stages, _ = read_fixture()
        generated = dict(build_sections())
        expected_names = {
            "highpass": "highpass_sos",
            "notch50": "notch_50hz_sos",
            "notch60": "notch_60hz_sos",
        }
        for fixture_name, generated_name in expected_names.items():
            np.testing.assert_allclose(
                fixture_stages[fixture_name],
                generated[generated_name],
                rtol=1e-6,
                atol=1e-7,
                err_msg=fixture_name,
            )

    def test_checked_in_vectors_match_pinned_scipy_reference_stage_by_stage(self):
        fixture_stages, values = read_fixture()
        self.assertEqual(values["input"].shape, (12,))
        signal = values["input"].astype(np.float64)
        for stage_name, expected_name in (
            ("highpass", "expected_highpass"),
            ("notch50", "expected_notch50"),
            ("notch60", "expected_notch60"),
        ):
            packed = fixture_stages[stage_name]
            sos = np.column_stack(
                (packed[:, :3], np.ones(len(packed)), packed[:, 3:5])
            ).astype(np.float64)
            zi = packed[:, 5:7].astype(np.float64)
            signal, _ = sosfilt(sos, signal, zi=zi * signal[0])
            np.testing.assert_allclose(
                signal,
                values[expected_name],
                rtol=2e-5,
                atol=2e-5,
                err_msg=stage_name,
            )


if __name__ == "__main__":
    unittest.main()

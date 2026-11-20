import csv
from pathlib import Path
import unittest

import numpy as np

FIXTURE = (
    Path(__file__).resolve().parents[3]
    / "app/src/test/resources/wakeword-feature-parity-v1.csv"
)


def read_expected():
    with FIXTURE.open(newline="", encoding="utf-8") as handle:
        rows = [row for row in csv.reader(handle) if row and not row[0].startswith("#")]
    if len(rows) != 1 or rows[0][0] != "expected":
        raise AssertionError("Malformed feature parity fixture")
    return np.asarray(rows[0][1:], dtype=np.float32)


def reference_features():
    index = np.arange(16_000, dtype=np.float64)
    seconds = index / 16_000.0
    samples = (
        (0.18 * np.sin(2 * np.pi * 440 * seconds)
         + 0.07 * np.cos(2 * np.pi * 997 * seconds))
        * (0.7 + 0.25 * np.sin(2 * np.pi * 3 * seconds))
    ).astype(np.float32)
    hann = (
        0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 400.0)
    ).astype(np.float32)
    mel = np.zeros((40, 257), dtype=np.float32)
    for row in range(40):
        mel[row, row * 3 + 1:row * 3 + 4] = (0.25, 0.5, 0.25)

    padded = np.pad(samples, (256, 256), mode="reflect")
    output = np.zeros((40, 101), dtype=np.float32)
    for frame in range(101):
        windowed = (
            padded[frame * 160 + 56:frame * 160 + 456] * hann
        ).astype(np.float32)
        fft_input = np.zeros(512, dtype=np.float64)
        fft_input[56:456] = windowed.astype(np.float64)
        spectrum = np.fft.rfft(fft_input)
        power = spectrum.real * spectrum.real + spectrum.imag * spectrum.imag
        for row in range(40):
            energy = 0.0
            for bin_index in range(257):
                energy += float(mel[row, bin_index]) * float(power[bin_index])
            output[row, frame] = np.float32(np.log(max(energy, 1e-9)))

    for row in range(40):
        mean = 0.0
        for frame in range(101):
            mean += float(output[row, frame])
        mean /= 101.0
        for frame in range(101):
            output[row, frame] = np.float32(float(output[row, frame]) - mean)
    return output.reshape(-1)


class FeatureParityVectorTest(unittest.TestCase):
    def test_shared_vector_matches_independent_numpy_reference(self):
        expected = read_expected()
        self.assertEqual(expected.shape, (40 * 101,))
        actual = reference_features()
        np.testing.assert_allclose(actual, expected, rtol=2e-5, atol=2e-5)


if __name__ == "__main__":
    unittest.main()

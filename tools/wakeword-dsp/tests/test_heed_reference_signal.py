"""Numerical reference tests using the pinned, real Heed DSP coefficients.

These tests deliberately exercise non-identity production coefficients. They
are a Python/SciPy reference gate; Kotlin parity still requires a shared golden
vector and must not be inferred from these tests alone.
"""
import unittest

import numpy as np
import scipy.signal

from generate_dsp_v1 import build_sections


class HeedReferenceSignalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sections = dict(build_sections())
        cls.filters = (
            ("highpass_sos", sections["highpass_sos"]),
            ("notch_50hz_sos", sections["notch_50hz_sos"]),
            ("notch_60hz_sos", sections["notch_60hz_sos"]),
        )
        cls.hann = sections["hann_window"]
        cls.mel = sections["mel_filterbank"]

    @staticmethod
    def signal():
        sample = np.arange(16_000, dtype=np.float64) / 16_000.0
        # Deterministic multi-tone speech-band proxy plus low-frequency hum.
        return (
            0.17 * np.sin(2 * np.pi * 137.0 * sample)
            + 0.11 * np.sin(2 * np.pi * 440.0 * sample)
            + 0.07 * np.sin(2 * np.pi * 997.0 * sample)
            + 0.04 * np.sin(2 * np.pi * 50.0 * sample)
            + 0.03 * np.sin(2 * np.pi * 60.0 * sample)
        ).astype(np.float64)

    def filtered_signal(self):
        actual = self.signal()
        for name, packed in self.filters:
            sos = np.column_stack((
                packed[:, :3],
                np.ones(packed.shape[0]),
                packed[:, 3:5],
            )).astype(np.float64)
            zi = np.asarray(packed[:, 5:7], dtype=np.float64)
            self.assertTrue(np.isfinite(sos).all(), name)
            self.assertTrue(np.isfinite(zi).all(), name)
            actual, _ = scipy.signal.sosfilt(sos, actual, zi=zi * actual[0])
            self.assertTrue(np.isfinite(actual).all(), name)
        return actual

    def test_pinned_heed_coefficients_produce_finite_nontrivial_reference(self):
        actual = self.filtered_signal()
        self.assertEqual(actual.shape, (16_000,))
        self.assertGreater(float(np.max(np.abs(actual))), 1e-5)
        self.assertFalse(np.allclose(actual, self.signal(), rtol=1e-5, atol=1e-6))

    def test_real_heed_filter_streaming_chunks_match_one_shot_reference(self):
        signal = self.signal()
        for name, packed in self.filters:
            sos = np.column_stack((
                packed[:, :3],
                np.ones(packed.shape[0]),
                packed[:, 3:5],
            )).astype(np.float64)
            zi = np.asarray(packed[:, 5:7], dtype=np.float64)
            expected, _ = scipy.signal.sosfilt(sos, signal, zi=zi * signal[0])

            state = zi * signal[0]
            chunks = []
            for start, end in ((0, 997), (997, 4096), (4096, 8193),
                               (8193, 12_345), (12_345, 16_000)):
                output, state = scipy.signal.sosfilt(
                    sos, signal[start:end], zi=state
                )
                chunks.append(output)
            streamed = np.concatenate(chunks)
            np.testing.assert_allclose(
                streamed, expected, rtol=1e-11, atol=1e-12, err_msg=name
            )

    def test_filter_stages_are_ordered_and_use_actual_heed_profiles(self):
        self.assertEqual([name for name, _ in self.filters], [
            "highpass_sos", "notch_50hz_sos", "notch_60hz_sos"
        ])
        self.assertEqual([array.shape for _, array in self.filters],
                         [(4, 7), (1, 7), (1, 7)])
        # Ensure the profile is not an identity-only test fixture.
        for name, array in self.filters:
            self.assertGreater(float(np.max(np.abs(array[:, :5] -
                np.tile(np.array([1, 0, 0, 0, 0], dtype=np.float32),
                        (array.shape[0], 1))))), 1e-7, name)

    def test_real_heed_filterbank_runs_full_log_mel_cmn_pipeline(self):
        filtered = self.filtered_signal()
        padded = np.pad(filtered, (256, 256), mode="reflect")
        features = np.empty((40, 101), dtype=np.float32)

        for frame in range(101):
            start = frame * 160 + 56
            windowed = padded[start:start + 400] * self.hann
            fft_input = np.zeros(512, dtype=np.float64)
            fft_input[56:456] = windowed
            spectrum = np.fft.rfft(fft_input)
            power = spectrum.real * spectrum.real + spectrum.imag * spectrum.imag
            mel_energy = self.mel.astype(np.float64) @ power
            features[:, frame] = np.log(np.maximum(mel_energy, 1e-9)).astype(np.float32)

        features -= features.mean(axis=1, keepdims=True)
        self.assertEqual(features.shape, (40, 101))
        self.assertTrue(np.isfinite(features).all())
        np.testing.assert_allclose(
            features.mean(axis=1), np.zeros(40, dtype=np.float32),
            rtol=0.0, atol=2e-6,
        )
        self.assertGreater(float(np.max(np.abs(features))), 1e-3)
        self.assertGreater(float(np.std(features)), 1e-2)


if __name__ == "__main__":
    unittest.main()

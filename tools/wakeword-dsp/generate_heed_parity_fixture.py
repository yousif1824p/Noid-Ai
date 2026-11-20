"""Generate shared real-Heed DSP fixtures for the Kotlin parity test."""
import sys
from pathlib import Path

import numpy as np
import scipy.signal

from generate_dsp_v1 import build_sections


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    sections = dict(build_sections())
    signal_time = np.arange(16_000, dtype=np.float64) / 16_000.0
    samples = (
        0.17 * np.sin(2 * np.pi * 137.0 * signal_time)
        + 0.11 * np.sin(2 * np.pi * 440.0 * signal_time)
        + 0.07 * np.sin(2 * np.pi * 997.0 * signal_time)
        + 0.04 * np.sin(2 * np.pi * 50.0 * signal_time)
        + 0.03 * np.sin(2 * np.pi * 60.0 * signal_time)
    ).astype(np.float32).astype(np.float64)

    filtered = samples
    for name in ("highpass_sos", "notch_50hz_sos", "notch_60hz_sos"):
        packed = sections[name]
        sos = np.column_stack((packed[:, :3], np.ones(packed.shape[0]), packed[:, 3:5])).astype(np.float64)
        zi = packed[:, 5:7].astype(np.float64)
        filtered, _ = scipy.signal.sosfilt(sos, filtered, zi=zi * filtered[0])

    hann = sections["hann_window"]
    mel = sections["mel_filterbank"].astype(np.float64)
    padded = np.pad(filtered, (256, 256), mode="reflect")
    features = np.empty((40, 101), dtype=np.float32)
    for frame in range(101):
        start = frame * 160 + 56
        windowed = padded[start:start + 400] * hann
        fft_input = np.zeros(512, dtype=np.float64)
        fft_input[56:456] = windowed
        spectrum = np.fft.rfft(fft_input)
        power = spectrum.real * spectrum.real + spectrum.imag * spectrum.imag
        energies = mel @ power
        features[:, frame] = np.log(np.maximum(energies, 1e-9)).astype(np.float32)
    features -= features.mean(axis=1, keepdims=True)

    payload = b"".join(array.astype("<f4", copy=False).tobytes(order="C")
                       for _, array in build_sections())
    if len(payload) != 42_888 or features.shape != (40, 101):
        raise RuntimeError("Unexpected Heed fixture geometry")
    (output / "heed_v1_payload.bin").write_bytes(payload)
    (output / "heed_v1_features.bin").write_bytes(features.astype("<f4", copy=False).tobytes(order="C"))


if __name__ == "__main__":
    main()

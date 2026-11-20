import hashlib
import json
import unittest

from generate_dsp_v1 import SHAPES, build_sections, generate


class DspV1GeneratorTests(unittest.TestCase):
    def test_real_heed_sections_have_contract_shapes_and_finite_values(self):
        import numpy as np
        sections = build_sections()
        self.assertEqual([name for name, _ in sections], [name for name, _ in SHAPES])
        for (name, array), (_, shape) in zip(sections, SHAPES):
            self.assertEqual(tuple(array.shape), shape, name)
            self.assertEqual(array.dtype, np.dtype("<f4"), name)
            self.assertTrue(np.isfinite(array).all(), name)

    def test_generation_has_exact_layout_and_valid_hashes(self):
        descriptor_bytes, payload = generate("heed-ar-iq-v1")
        descriptor = json.loads(descriptor_bytes)
        self.assertEqual(len(payload), 42888)
        self.assertEqual(descriptor["payload_bytes"], len(payload))
        self.assertEqual(descriptor["payload_sha256"], hashlib.sha256(payload).hexdigest())
        self.assertEqual(descriptor["profile_id"], "heed-ar-iq-v1")
        self.assertEqual(len(descriptor["sections"]), 5)
        expected_offset = 0
        for section, (_, shape) in zip(descriptor["sections"], SHAPES):
            self.assertEqual(section["offset_bytes"], expected_offset)
            self.assertEqual(section["shape"], list(shape))
            size = section["element_count"] * 4
            raw = payload[expected_offset:expected_offset + size]
            self.assertEqual(section["sha256"], hashlib.sha256(raw).hexdigest())
            expected_offset += size
        self.assertEqual(expected_offset, len(payload))

    def test_repeated_generation_is_deterministic_in_same_environment(self):
        self.assertEqual(generate("determinism-check"), generate("determinism-check"))

    def test_rejects_unsafe_profile_ids(self):
        for profile_id in ("", "../escape", "UpperCase", "a" * 65, "has space"):
            with self.subTest(profile_id=profile_id):
                with self.assertRaises(ValueError):
                    generate(profile_id)


if __name__ == "__main__":
    unittest.main()

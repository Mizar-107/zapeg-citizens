from __future__ import annotations

import unittest

from citizen_brain.textfold import fold


class TextFoldTest(unittest.TestCase):
    def test_folds_every_turkish_i_variant_to_plain_i(self) -> None:
        self.assertEqual("balta", fold("BALTA"))
        self.assertEqual("iptal", fold("İptal"))
        self.assertEqual("iptal", fold("İPTAL"))
        self.assertEqual("iptal", fold("ıptal"))
        self.assertEqual("iptal", fold("i̇ptal"))
        self.assertEqual("sandik", fold("SANDIK"))
        self.assertEqual("sandik", fold("sandık"))

    def test_preserves_other_turkish_letters_and_english(self) -> None:
        self.assertEqual("kürek", fold("KÜREK"))
        self.assertEqual("kömür", fold("Kömür"))
        self.assertEqual("ağaç", fold("AĞAÇ"))
        self.assertEqual("chop 8 logs", fold("Chop 8 LOGS"))
        self.assertEqual("", fold(None))
        self.assertEqual("", fold(""))


if __name__ == "__main__":
    unittest.main()

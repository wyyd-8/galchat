import unittest

from character_card.renderer import Renderer, resolve_font


class CharacterCardPdfTest(unittest.TestCase):

    def assert_position(self, expected, actual):
        self.assertAlmostEqual(expected[0], actual[0])
        self.assertAlmostEqual(expected[1], actual[1])

    def test_known_state_track_coordinates(self):
        self.assert_position((128.8, 597.2), Renderer.hp_track_position(0))
        self.assert_position((128.8, 562.0), Renderer.hp_track_position(13))
        self.assert_position((300.4, 581.6), Renderer.san_track_position(60))
        self.assert_position((486.8, 516.4), Renderer.mp_track_position(12))

    def test_track_boundaries(self):
        self.assert_position((167.2, 550.4), Renderer.hp_track_position(20))
        self.assert_position((530.8, 569.6), Renderer.san_track_position(99))
        self.assert_position((523.6, 493.8), Renderer.mp_track_position(24))

    def test_three_bundled_font_indexes(self):
        for index in range(3):
            self.assertTrue(resolve_font(index).is_file())


if __name__ == "__main__":
    unittest.main()

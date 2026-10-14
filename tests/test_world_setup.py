import json
import sys
import tempfile
import tomllib
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from install_world_prototype import install,profile
from build_world_prototype import DEFAULT
DATA=json.loads(DEFAULT.read_text())

class WorldSetupTests(unittest.TestCase):
    def test_source_checked_profile_and_original_package(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            paths=install(root,DATA)
            self.assertEqual(len(paths),4)
            selected=tomllib.loads((root/'defaultconfigs/lostcities-server.toml').read_text())
            self.assertEqual(selected['profiles']['selectedProfile'],'uc_three_city')
            settings=json.loads((root/'config/lostcities/profiles/uc_three_city.json').read_text())
            self.assertEqual(settings['cities']['cityChance'],0)
            self.assertEqual(settings['lostcity']['worldStyle'],'underground_city:three_city')
            common=tomllib.loads((root/'config/lostcities/common.toml').read_text())
            self.assertIn('minecraft:overworld=uc_three_city',common['profiles']['dimensionsWithProfiles'])
            self.assertGreaterEqual(settings['cities']['cityMaxRadius'],600)
            self.assertFalse(settings['lostcity']['generateSpawners'])
            self.assertEqual(settings['explosions']['explosionChance'],0)
            self.assertEqual(len(list(root.rglob('*.jar'))),1)

    def test_preserve_renamed_existing_saves_and_configs(self):
        for relative in ['saves/private/level.dat','region/level.dat_old','defaultconfigs/lostcities-server.toml','config/lostcities/common.toml']:
            with self.subTest(relative=relative),tempfile.TemporaryDirectory() as directory:
                root=Path(directory); path=root/relative; path.parent.mkdir(parents=True); path.write_text('PRESERVE')
                with self.assertRaises(ValueError): install(root,DATA)
                self.assertEqual(path.read_text(),'PRESERVE')
                self.assertFalse((root/'mods').exists())

if __name__=='__main__': unittest.main()

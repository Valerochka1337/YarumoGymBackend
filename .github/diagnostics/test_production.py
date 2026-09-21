"""Run before SSH: a JVM startup option is not an OOM event."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('production', Path(__file__).with_name('production.py'))
production = importlib.util.module_from_spec(spec)
spec.loader.exec_module(production)


class OomFilterTest(unittest.TestCase):
    def test_jvm_startup_options_do_not_count_as_oom(self):
        self.assertEqual(production.summarize(
            'Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError'
        )['oom_mentions'], 0)

    def test_actual_java_and_native_ooms_are_counted(self):
        self.assertEqual(production.summarize(
            'java.lang.OutOfMemoryError: Java heap space\nNative memory allocation: out of memory'
        )['oom_mentions'], 2)


if __name__ == '__main__':
    unittest.main()

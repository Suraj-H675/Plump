"""Pure offline parser tests; no device, APK or personal data is used."""

import importlib.util
from pathlib import Path
import unittest


path = Path(__file__).parent / 'parse-p3-performance.py'
spec = importlib.util.spec_from_file_location('p3_performance', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def sample(timestamp, count, rejected=0, underruns=0):
    return (f'{timestamp:.3f} 1024 1234 I PlumpDS: '
            f'frames={count} posted={count-rejected} rejected={rejected} '
            'measuredFps=59.80 targetFps=59.83 avgCore=8ms '
            f'peakCore=21ms p95Core=11ms p99Core=15ms audioUnderruns={underruns}')


class PerformanceParserTest(unittest.TestCase):
    def test_real_frame_deltas_are_measured(self):
        lines = [sample(1000, 10), sample(1003, 190),
                 sample(1006, 370), sample(1009, 550), sample(1012, 730)]
        report = module.summarize(lines, 999, 1013)
        self.assertEqual(report['emulated_frames_delta'], 540)
        self.assertEqual(report['actual_emulated_fps'], 60)
        self.assertEqual(report['rejected_frames_delta'], 0)
        self.assertEqual(report['audio_underrun_count_delta'], 0)
        self.assertEqual(report['median_interval_p95_core_ms'], 11)

    def test_reports_not_hides_frame_losses_and_underruns(self):
        lines = [sample(1000, 10), sample(1003, 190),
                 sample(1006, 300, 2, 1), sample(1009, 400, 5, 4)]
        report = module.summarize(lines, 999, 1010)
        self.assertEqual(report['rejected_frames_delta'], 5)
        self.assertEqual(report['audio_underrun_count_delta'], 4)
        self.assertIn('needs investigation', report['interpretation'])

    def test_rejects_invalid_stale_or_reset_log(self):
        with self.assertRaisesRegex(ValueError, 'Too few'):
            module.summarize([sample(1000, 10)], 1001, 1005)
        with self.assertRaisesRegex(ValueError, 'reset'):
            module.summarize([sample(1000, 50), sample(1003, 100),
                              sample(1006, 10), sample(1009, 200)], 999, 1010)

    def test_rejects_silent_background_gap(self):
        with self.assertRaisesRegex(ValueError, 'Long missing-log gap'):
            module.summarize([sample(1000, 10), sample(1003, 190),
                              sample(1025, 370), sample(1028, 550)], 999, 1030)


if __name__ == '__main__':
    unittest.main()

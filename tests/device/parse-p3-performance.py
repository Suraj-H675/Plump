#!/usr/bin/env python3
"""Summarize *real device* PlumpDS log frames, never invent FPS or thermals.

Consumes only the PlumpDS app-tagged logcat record collected by the shell
helper. A CPU core timing window is not an end-to-end presentation latency.
"""

import argparse
import json
import re
from pathlib import Path
from statistics import median


PATTERN = re.compile(
    r'^\s*(?P<timestamp>\d+\.\d+).*?PlumpDS\s*:\s*'
    r'frames=(?P<frames>\d+) posted=(?P<posted>\d+) rejected=(?P<rejected>\d+)'
    r' measuredFps=(?P<fps>[\d.]+) targetFps=(?P<target>[\d.]+)'
    r' avgCore=(?P<avg>\d+)ms peakCore=(?P<peak>\d+)ms'
    r' p95Core=(?P<p95>\d+)ms p99Core=(?P<p99>\d+)ms'
    r' audioUnderruns=(?P<audio>\d+)'
)


def summarize(lines, start_epoch, end_epoch):
    entries = []
    for line in lines:
        match = PATTERN.search(line)
        if not match:
            continue
        value = {key: float(val) if key in ('timestamp', 'fps', 'target') else int(val)
                 for key, val in match.groupdict().items()}
        if start_epoch <= value['timestamp'] <= end_epoch:
            entries.append(value)

    if len(entries) < 3:
        raise ValueError('Too few fresh PlumpDS intervals; game may be paused or not in foreground')
    if any(b['frames'] <= a['frames'] or b['posted'] < a['posted']
           or b['rejected'] < a['rejected'] or b['audio'] < a['audio']
           for a, b in zip(entries, entries[1:])):
        raise ValueError('Frame/audio counters reset or moved backwards during sample')
    if any(b['timestamp'] - a['timestamp'] > 12.0
           for a, b in zip(entries, entries[1:])):
        raise ValueError('Long missing-log gap: possible backgrounding or process stall')

    # Ignore initial interval for warmup. Compute FPS from timestamp/frame
    # deltas rather than trusting a hardcoded 60 FPS or any one HUD sample.
    samples = entries[1:]
    elapsed = samples[-1]['timestamp'] - samples[0]['timestamp']
    if elapsed <= 0:
        raise ValueError('No positive timing interval')
    frame_delta = samples[-1]['frames'] - samples[0]['frames']
    posted_delta = samples[-1]['posted'] - samples[0]['posted']
    rejected_delta = samples[-1]['rejected'] - samples[0]['rejected']
    underrun_delta = samples[-1]['audio'] - samples[0]['audio']
    if frame_delta <= 0:
        raise ValueError('No real emulated frame progression')
    target = median(item['target'] for item in samples)
    actual = frame_delta / elapsed
    return {
        'duration_seconds_after_warmup': round(elapsed, 1),
        'metric_intervals': len(samples),
        'emulated_frames_delta': frame_delta,
        'posted_frames_delta': posted_delta,
        'rejected_frames_delta': rejected_delta,
        'actual_emulated_fps': round(actual, 2),
        'native_target_fps': target,
        'percent_native_speed': round(100 * actual / target, 1),
        'posted_to_emulated_ratio': round(posted_delta / frame_delta, 4),
        'median_interval_p95_core_ms': median(item['p95'] for item in samples),
        'worst_interval_p99_core_ms': max(item['p99'] for item in samples),
        'worst_interval_peak_core_ms': max(item['peak'] for item in samples),
        'audio_underrun_count_delta': underrun_delta,
        'interpretation': (
            'Diagnostic met >=95% native emulation speed with zero rejected frames'
            if actual >= target * .95 and rejected_delta == 0 else
            'Performance/frame presentation needs investigation'
        ),
        'limits': 'No full game compatibility, GPU frame-presentation latency or acoustic audio-sync claim',
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('log', type=Path)
    parser.add_argument('start_epoch', type=float)
    parser.add_argument('end_epoch', type=float)
    parser.add_argument('output_json', type=Path)
    args = parser.parse_args()
    metrics = summarize(args.log.read_text().splitlines(), args.start_epoch, args.end_epoch)
    args.output_json.write_text(json.dumps(metrics, indent=2) + '\n')
    print(json.dumps(metrics, indent=2))


if __name__ == '__main__':
    main()

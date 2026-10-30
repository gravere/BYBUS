"""Describe reusable raw ZIP payloads in one signed APK, independent of old builds."""
from pathlib import Path
import argparse
import json
import zipfile
from apk_delta import LIMIT, digest, digest_range, payload

FORMAT = 'bybus-range-v1'
MAX_ENTRIES = 32
MIN_BYTES = 64 * 1024


def describe(target):
    target = Path(target)
    size = target.stat().st_size
    if not 0 < size <= LIMIT:
        raise ValueError('APK outside size limit')
    with zipfile.ZipFile(target) as archive, target.open('rb') as stream:
        entries = archive.infolist()
        if len(entries) > 50_000 or len({e.filename for e in entries}) != len(entries):
            raise ValueError('Ambiguous or excessive ZIP entries')
        candidates = [e for e in entries if e.compress_size >= MIN_BYTES and not e.is_dir()
                      and e.compress_type in (0, 8) and not e.flag_bits & 1
                      and 0 < len(e.filename) <= 512 and not any(ord(c) < 32 for c in e.filename)]
        candidates = sorted(candidates, key=lambda e: e.compress_size, reverse=True)[:MAX_ENTRIES]
        result, end = [], 0
        for entry in sorted(candidates, key=lambda e: e.header_offset):
            start = payload(stream, entry, size)
            if start < end:
                raise ValueError('Overlapping payloads')
            result.append(dict(name=entry.filename, offset=start, length=entry.compress_size,
                               sha256=digest_range(stream, start, entry.compress_size).hex()))
            end = start + entry.compress_size
    return dict(format=FORMAT, entries=result)


def plan(base, description, target_size):
    """Offline reference planner for release verification; never needs a version list."""
    base = Path(base)
    result = []
    with zipfile.ZipFile(base) as archive, base.open('rb') as stream:
        entries = archive.infolist()
        old = {e.filename: e for e in entries}
        if len(old) != len(entries):
            raise ValueError('Duplicate base entries')
        for wanted in description['entries']:
            entry = old.get(wanted['name'])
            if entry is None or entry.compress_size != wanted['length'] or entry.flag_bits & 1:
                continue
            start = payload(stream, entry, base.stat().st_size)
            if digest_range(stream, start, entry.compress_size).hex() == wanted['sha256']:
                result.append(dict(source=start, offset=wanted['offset'], length=entry.compress_size))
    return dict(download_bytes=target_size-sum(e['length'] for e in result), reused=result)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', type=Path, required=True)
    parser.add_argument('--base', type=Path, action='append', default=[])
    args = parser.parse_args()
    description = describe(args.target)
    print(json.dumps(dict(target_sha256=digest(args.target), reuse=description,
        bases=[dict(name=b.name, **plan(b, description, args.target.stat().st_size)) for b in args.base]), indent=2))

"""Exact APK delta: GZIP(BYBDLT01 + sizes/hashes/count + COPY/DATA commands).

Integers are unsigned big endian (bounded below signed Java limits). COPY=0,
u64 source offset, u64 length; DATA=1, u64 length, literal bytes. ZIP payloads
are copied as stored, including compression; all other target bytes are literal.
No external codec, ZIP recompression, or signing key is required to apply a patch.
"""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import struct
import zipfile

MAGIC = b'BYBDLT01'
FORMAT = 'bybus-copy-v1'
LIMIT = 256 * 1024 * 1024
MAX_OPERATIONS = 100_000
BLOCK = 64 * 1024


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_exact(stream, size):
    data = stream.read(size)
    if len(data) != size:
        raise ValueError('Truncated delta or APK')
    return data


def payload(stream, entry, file_size):
    stream.seek(entry.header_offset)
    header = read_exact(stream, 30)
    if header[:4] != b'PK\x03\x04' or entry.flag_bits & 1:
        raise ValueError('Unsupported ZIP entry')
    name_size, extra_size = struct.unpack_from('<HH', header, 26)
    start = entry.header_offset + 30 + name_size + extra_size
    if not 0 <= start <= file_size or not 0 <= entry.compress_size <= file_size - start:
        raise ValueError('ZIP payload outside APK')
    return start


def digest_range(stream, offset, size):
    stream.seek(offset)
    sha = hashlib.sha256()
    while size:
        part = read_exact(stream, min(size, BLOCK))
        sha.update(part)
        size -= len(part)
    return sha.digest()


def create_patch(base, target, patch):
    base, target, patch = map(Path, (base, target, patch))
    if patch.resolve() in (base.resolve(), target.resolve()):
        raise ValueError('Output must not replace an APK')
    base_size, target_size = base.stat().st_size, target.stat().st_size
    if not (0 < base_size <= LIMIT and 0 < target_size <= LIMIT):
        raise ValueError('APK size outside limit')
    base_sha, target_sha = digest(base), digest(target)
    commands = []

    def append(kind, offset, length):
        if length:
            if commands and commands[-1][0] == kind and commands[-1][1] + commands[-1][2] == offset:
                previous = commands[-1]
                commands[-1] = (kind, previous[1], previous[2] + length)
            else:
                commands.append((kind, offset, length))

    with zipfile.ZipFile(base) as old_zip, zipfile.ZipFile(target) as new_zip, base.open('rb') as old, target.open('rb') as new:
        old_entries = {e.filename: e for e in old_zip.infolist()}
        new_entries = sorted(new_zip.infolist(), key=lambda e: e.header_offset)
        if len(old_entries) != len(old_zip.infolist()) or len({e.filename for e in new_entries}) != len(new_entries):
            raise ValueError('Duplicate ZIP entries')
        if max(len(old_entries), len(new_entries)) > 50_000:
            raise ValueError('Too many ZIP entries')
        cursor = 0
        for entry in new_entries:
            previous = old_entries.get(entry.filename)
            if not previous or entry.compress_size < 256:
                continue
            if (entry.compress_type, entry.compress_size, entry.file_size, entry.CRC) != (
                    previous.compress_type, previous.compress_size, previous.file_size, previous.CRC):
                continue
            start = payload(new, entry, target_size)
            old_start = payload(old, previous, base_size)
            if start < cursor:
                raise ValueError('Overlapping ZIP payloads')
            if digest_range(old, old_start, entry.compress_size) != digest_range(new, start, entry.compress_size):
                continue
            append(1, cursor, start - cursor)
            append(0, old_start, entry.compress_size)
            cursor = start + entry.compress_size
        append(1, cursor, target_size - cursor)
        if not 0 < len(commands) <= MAX_OPERATIONS:
            raise ValueError('Too many delta commands')
        patch.parent.mkdir(parents=True, exist_ok=True)
        pending = patch.with_suffix(patch.suffix + '.part')
        try:
            with pending.open('wb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0, compresslevel=6) as output:
                output.write(MAGIC + struct.pack('>QQ', base_size, target_size))
                output.write(bytes.fromhex(base_sha) + bytes.fromhex(target_sha) + struct.pack('>I', len(commands)))
                for kind, offset, length in commands:
                    output.write(bytes([kind]))
                    if kind == 0:
                        output.write(struct.pack('>QQ', offset, length))
                    else:
                        output.write(struct.pack('>Q', length))
                        new.seek(offset)
                        while length:
                            block = read_exact(new, min(length, BLOCK))
                            output.write(block)
                            length -= len(block)
            pending.replace(patch)
        finally:
            pending.unlink(missing_ok=True)
    verification = patch.with_suffix(patch.suffix + '.verify.part')
    try:
        apply_patch(base, patch, verification, target_sha)
    except Exception:
        patch.unlink(missing_ok=True)
        raise
    finally:
        verification.unlink(missing_ok=True)
    return dict(format=FORMAT, fromSize=base_size, fromSha256=base_sha, name=patch.name,
                size=patch.stat().st_size, sha256=digest(patch))


def apply_patch(base, patch, output, expected_target_sha):
    base, patch, output = map(Path, (base, patch, output))
    if output.resolve() in (base.resolve(), patch.resolve()):
        raise ValueError('Output must not replace its inputs')
    complete = False
    try:
        with gzip.open(patch, 'rb') as source, base.open('rb') as old:
            if read_exact(source, 8) != MAGIC:
                raise ValueError('Unsupported delta format')
            base_size, target_size = struct.unpack('>QQ', read_exact(source, 16))
            base_sha, target_sha = read_exact(source, 32).hex(), read_exact(source, 32).hex()
            count, = struct.unpack('>I', read_exact(source, 4))
            if not (0 < base_size <= LIMIT and 0 < target_size <= LIMIT and 0 < count <= MAX_OPERATIONS):
                raise ValueError('Delta limits exceeded')
            if base.stat().st_size != base_size or digest(base) != base_sha or target_sha != expected_target_sha:
                raise ValueError('Wrong base or target APK')
            sha, total = hashlib.sha256(), 0
            with output.open('wb') as result:
                for _ in range(count):
                    kind = read_exact(source, 1)[0]
                    if kind not in (0, 1):
                        raise ValueError('Unknown operation')
                    offset = struct.unpack('>Q', read_exact(source, 8))[0] if kind == 0 else 0
                    length, = struct.unpack('>Q', read_exact(source, 8))
                    if not 0 < length <= target_size - total:
                        raise ValueError('Output limit exceeded')
                    if kind == 0:
                        if offset > base_size or length > base_size - offset:
                            raise ValueError('Source range outside base APK')
                        old.seek(offset)
                    remaining = length
                    while remaining:
                        data = read_exact(old if kind == 0 else source, min(BLOCK, remaining))
                        result.write(data)
                        sha.update(data)
                        remaining -= len(data)
                        total += len(data)
                if total != target_size or source.read(1) or sha.hexdigest() != target_sha:
                    raise ValueError('Reconstructed APK differs from target')
        complete = True
    finally:
        if not complete:
            output.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, type=Path)
    parser.add_argument('--target', required=True, type=Path)
    parser.add_argument('--patch', required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(create_patch(args.base, args.target, args.patch)))

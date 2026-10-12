"""Fetch pinned, unmodified microG release assets before building. Python 3.10+."""
from pathlib import Path
from contextlib import ExitStack
import argparse, hashlib, os, urllib.request, urllib.parse

BASE = 'https://github.com/microg/GmsCore/releases/download/v0.3.17.252432/'
ASSETS = [
    ('com.google.android.gms-252432034.apk', 108113833,
     'e5e6cba078bdec812b5450599350e5c1a12c8b7438da5bf77123af170f8c1a35',
     ['com.google.android.gms-252432034.apk.part00', 'com.google.android.gms-252432034.apk.part01']),
    ('com.android.vending-84022634.apk', 4210169,
     '9b8616011cc8f026bcbbe094df850794abb9d6d9f23214d7a8f8c4d1033ea012',
     ['com.android.vending-84022634.apk']),
]
PART = 64 * 1024 * 1024

class GitHubRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        uri = urllib.parse.urlparse(newurl)
        if uri.scheme != 'https' or uri.hostname not in ('github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com') or uri.username or uri.password:
            raise ValueError('Untrusted asset redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def matches(files, size, expected):
    if not all(p.is_file() for p in files) or sum(p.stat().st_size for p in files) != size:
        return False
    digest = hashlib.sha256()
    for p in files:
        with p.open('rb') as f:
            while block := f.read(128 * 1024): digest.update(block)
    return digest.hexdigest() == expected

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Only verify local assets; do not download')
    args = parser.parse_args()
    folder = Path(__file__).resolve().parent.parent/'app/src/main/assets/microg'
    folder.mkdir(parents=True, exist_ok=True)
    for name, size, expected, names in ASSETS:
        files = [folder/n for n in names]
        if matches(files, size, expected):
            print('Verified: ' + name)
            continue
        if args.check: raise SystemExit('Missing or changed asset. Run python scripts/fetch-build-assets.py')
        pending = [p.with_suffix(p.suffix+'.download') for p in files]
        try:
            request = urllib.request.Request(BASE+name, headers={'User-Agent':'BYBUS-build-assets'})
            digest = hashlib.sha256(); total = 0
            with urllib.request.build_opener(GitHubRedirect()).open(request, timeout=30) as response, ExitStack() as stack:
                outputs = [stack.enter_context(p.open('wb')) for p in pending]
                while block := response.read(128 * 1024):
                    if total+len(block) > size: raise ValueError('Asset exceeds pinned size')
                    digest.update(block)
                    # 128 KiB divides the 64 MiB part boundary; small network reads may not.
                    offset = 0
                    while offset < len(block):
                        part = total // PART if len(outputs) > 1 else 0
                        count = min(len(block)-offset, PART-total % PART)
                        outputs[part].write(block[offset:offset+count]); offset += count; total += count
                for output in outputs: output.flush(); os.fsync(output.fileno())
            if total != size or digest.hexdigest() != expected: raise ValueError('Asset checksum mismatch')
            for source, target in zip(pending, files): source.replace(target)
            print('Downloaded and verified: ' + name)
        finally:
            for p in pending: p.unlink(missing_ok=True)

if __name__ == '__main__': main()

"""Generate public release assets from a built APK. Never reads signing credentials."""
from pathlib import Path
import argparse, hashlib, json, re, shutil, subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--apk', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--aapt', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
badging = subprocess.check_output([args.aapt, 'dump', 'badging', str(args.apk.resolve())], text=True, encoding='utf-8')
identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
assert identity and identity[1] == 'com.example.limbuszhcn', 'Not a BYBUS APK'
version, code = identity[3], int(identity[2])
min_sdk = int(re.search(r"sdkVersion:'(\d+)'", badging)[1])
assert re.fullmatch(r'v\d+(?:\.\d+){1,2}', version)
gradle = (root/'app/build.gradle.kts').read_text(encoding='utf-8')
assert code == int(re.search(r'versionCode\s*=\s*(\d+)', gradle)[1]), 'Build differs from source version'
assert version == re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
apksigner = Path(args.aapt).with_name('apksigner.bat' if Path(args.aapt).suffix == '.exe' else 'apksigner')
verification = subprocess.check_output([str(apksigner), 'verify', '--print-certs', str(args.apk.resolve())], text=True, encoding='utf-8')
signer = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})', verification)
assert signer, 'APK signature not verified'
args.output.mkdir(parents=True, exist_ok=True)
apk = args.output/'BYBUS.apk'
if apk.resolve() != args.apk.resolve(): shutil.copyfile(args.apk, apk)
sha = hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()
notes = (root/'docs/release-notes.md').read_text(encoding='utf-8')
manifest = dict(schemaVersion=1, packageName=identity[1], versionName=version, versionCode=code,
                minSdk=min_sdk, apkName=apk.name, size=apk.stat().st_size, sha256=sha,
                notes='\n'.join(line.removeprefix('- ') for line in notes.splitlines() if line.startswith('- ')))
(args.output/'bybus-update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
files = [apk, args.output/'bybus-update.json']
(args.output/'SHA256SUMS.txt').write_text(''.join(hashlib.file_digest(p.open('rb'), 'sha256').hexdigest()+'  '+p.name+'\n' for p in files), encoding='utf-8')
print(json.dumps(dict(tag=f'{version}-build.{code}', sha256=sha, size=apk.stat().st_size, signer=signer[1])))

"""Generate named Android release assets and metadata from a signed BYBUS APK."""
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
native_code = re.search(r'^native-code:(.*)$', badging, re.MULTILINE)
assert native_code and re.findall(r"'([^']+)'", native_code[1]) == ['arm64-v8a'], 'Unexpected APK architecture'
# The static FileProvider URI helper reads manifest metadata, not a subclass constructor.
manifest_tree = subprocess.check_output([args.aapt, 'dump', 'xmltree', str(args.apk.resolve()), 'AndroidManifest.xml'], text=True, encoding='utf-8')
nodes = manifest_tree.splitlines()
providers = []
for i, line in enumerate(nodes):
    if line.lstrip().startswith('E: provider '):
        indent = len(line) - len(line.lstrip())
        end = next((j for j in range(i+1, len(nodes)) if nodes[j].lstrip().startswith('E: ') and len(nodes[j])-len(nodes[j].lstrip()) <= indent), len(nodes))
        block = '\n'.join(nodes[i:end])
        if f'="{identity[1]}.app-updates"' in block:
            providers.append(block)
assert len(providers) == 1, 'Missing or duplicate app update FileProvider'
provider = providers[0]
assert re.search(r'android:exported\([^)]*\)=\(type 0x12\)0x0\b', provider), 'Update provider must not be exported'
assert re.search(r'android:grantUriPermissions\([^)]*\)=\(type 0x12\)0xffffffff\b', provider), 'Update provider must allow temporary URI grants'
assert '="android.support.FILE_PROVIDER_PATHS"' in provider, 'Missing FileProvider paths metadata; app update installation would fail'
resource_ids = re.findall(r'android:resource\([^)]*\)=@(0x[a-fA-F0-9]+)', provider)
resources = subprocess.check_output([args.aapt, 'dump', 'resources', str(args.apk.resolve())], text=True, encoding='utf-8')
paths_id = re.search(r'resource (0x[a-fA-F0-9]+) '+re.escape(identity[1])+r':xml/app_update_paths:', resources)
assert paths_id and resource_ids == [paths_id[1]], 'Update provider points to the wrong paths resource'
apksigner = Path(args.aapt).with_name('apksigner.bat' if Path(args.aapt).suffix == '.exe' else 'apksigner')
verification = subprocess.check_output([str(apksigner), 'verify', '--print-certs', str(args.apk.resolve())], text=True, encoding='utf-8')
signer = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})', verification)
assert signer, 'APK signature not verified'
args.output.mkdir(parents=True, exist_ok=True)
apk = args.output/f'BYBUS-{version}-arm64-v8a.apk'
if not apk.exists() or not apk.samefile(args.apk):
    # Unlink before writing: a previous output may share an inode with its alias.
    if apk.exists(): apk.unlink()
    shutil.copyfile(args.apk, apk)
# Existing clients require this exact name. Both downloads contain the same APK.
compat_apk = args.output/'BYBUS.apk'
if not compat_apk.exists() or not compat_apk.samefile(apk):
    if compat_apk.exists(): compat_apk.unlink()
    try: compat_apk.hardlink_to(apk)
    except OSError: shutil.copyfile(apk, compat_apk)
def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()
sha = digest(apk)
notes = (root/'docs/release-notes.md').read_text(encoding='utf-8')
manifest = dict(schemaVersion=1, packageName=identity[1], versionName=version, versionCode=code,
                minSdk=min_sdk, apkName=compat_apk.name, size=apk.stat().st_size, sha256=sha,
                notes='\n'.join(line.removeprefix('- ') for line in notes.splitlines() if line.startswith('- ')))
(args.output/'bybus-update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
files = [apk, compat_apk, args.output/'bybus-update.json']
(args.output/'SHA256SUMS.txt').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in files), encoding='utf-8')
print(json.dumps(dict(tag=f'{version}-build.{code}', name=f'BYBUS {version}', apkName=apk.name,
                     compatibilityApkName=compat_apk.name, sha256=sha, size=apk.stat().st_size, signer=signer[1])))

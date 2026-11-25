"""Build a real Android ProcessBuilder probe around the production exec/chdir wrappers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import zipfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--abi',choices=['x86_64','arm64-v8a'],default='x86_64')
parser.add_argument('--baseline-vfork',type=Path,help='Archived old wrapper for a controlled before/after comparison')
args=parser.parse_args()
out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parent.parent
source_dir=root/'third_party/virtualapp-upstream/lib/src/main/jni/Foundation'
source=(source_dir/'IORelocator64.cpp').read_text('utf-8')
body=source[source.index('static int exec_elf_class('):source.index('HOOK_DEF(void *, dlopen_CI')]
body+=source[source.index('HOOK_DEF(int, chdir,'):source.index('// int truncate64')]
if args.baseline_vfork:
    body+=args.baseline_vfork.read_text('utf-8')
elif 'HOOK_SYMBOL(handle, vfork)' in source:
    raise SystemExit('The production vfork replacement is still installed')
(out/'process-under-test.inc').write_text(body,'utf-8')
sdk=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
windows=platform.system()=='Windows';suffix='.exe' if windows else ''
tools=sdk/'ndk/30.0.14904198/toolchains/llvm/prebuilt'/('windows-x86_64' if windows else 'linux-x86_64')/'bin'
target='x86_64-linux-android26' if args.abi=='x86_64' else 'aarch64-linux-android26'
flags=['--target='+target,'-O2','-g','-fPIC','-I'+str(source_dir),'-I'+str(out)]
subprocess.run([str(tools/('clang'+suffix)),*flags,'-c',str(source_dir/'canonicalize_md.c'),'-o',str(out/'canonicalize.o')],check=True)
subprocess.run([str(tools/('clang++'+suffix)),*flags,*(['-DTEST_BASELINE_VFORK=1'] if args.baseline_vfork else []),
    '-std=c++17','-shared','-static-libstdc++',str(root/'scripts/process-probe/process-preload.cpp'),
    str(source_dir/'SandboxFs.cpp'),str(out/'canonicalize.o'),'-llog','-o',str(out/'libprocessprobe.so')],check=True)
java=Path(os.environ['JAVA_HOME'])/'bin'
subprocess.run([str(java/('javac'+suffix)),'--release','8','-d',str(out),str(root/'scripts/process-probe/ProcessLaunchTest.java')],check=True)
subprocess.run([str(java/('java'+suffix)),'-cp',str(sdk/'build-tools/35.0.0/lib/d8.jar'),'com.android.tools.r8.D8',
    '--min-api','26','--lib',str(sdk/'platforms/android-35/android.jar'),'--output',str(out/'process-test.jar'),str(out/'ProcessLaunchTest.class')],check=True)
files=['libprocessprobe.so','process-test.jar']
manifest={'abi':args.abi,'baseline':bool(args.baseline_vfork),'wrapper_sha256':hashlib.sha256(body.encode()).hexdigest(),
    'files':{name:hashlib.sha256((out/name).read_bytes()).hexdigest() for name in files}}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2),'utf-8')
with zipfile.ZipFile(out/'bybus-process-probe.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for name in [*files,'manifest.json']:archive.write(out/name,name)
print(out/'bybus-process-probe.zip')

"""Build the IO logger regression executable; run it only on a disposable emulator."""
import argparse
import os
import platform
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], default='x86_64')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
source = root / 'third_party/virtualapp-upstream/lib/src/main/jni/Foundation'
sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
windows = platform.system() == 'Windows'
host = 'windows-x86_64' if windows else 'linux-x86_64'
toolchain = sdk / 'ndk/30.0.14904198/toolchains/llvm/prebuilt' / host / 'bin'
suffix = '.exe' if windows else ''
target = 'aarch64-linux-android26' if args.abi == 'arm64-v8a' else 'x86_64-linux-android26'
args.output.mkdir(parents=True, exist_ok=True)

def function(text, marker):
    start = text.index(marker)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

text = (source / 'IORelocator64.cpp').read_text(encoding='utf-8')
include = args.output / 'runtime-socket-io-under-test.inc'
include.write_text('\n'.join(function(text, marker) for marker in
    ['HOOK_DEF(ssize_t, sendto,', 'HOOK_DEF(int, connect,']), encoding='utf-8')
common = ['--target=' + target, '-O1', '-g', '-I' + str(source), '-I' + str(args.output)]
obj = args.output / 'canonicalize.o'
subprocess.run([str(toolchain / ('clang' + suffix)), *common, '-c',
    str(source / 'canonicalize_md.c'), '-o', str(obj)], check=True)
binary = args.output / 'io-logging'
subprocess.run([str(toolchain / ('clang++' + suffix)), *common, '-std=c++17', '-static-libstdc++',
    str(root / 'scripts/native-io-logging-tests.cpp'), str(source / 'MapsRedirector.cpp'),
    str(source / 'SandboxFs.cpp'), str(source / 'RuntimePage.cpp'), str(obj), '-o', str(binary)], check=True)
print(binary)

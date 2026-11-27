"""Build an optional ARM64 debug-only fixture for the production SVC bridge."""
from pathlib import Path
import os, platform, subprocess
root = Path(__file__).resolve().parent.parent
sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
host = 'windows-x86_64' if platform.system() == 'Windows' else 'linux-x86_64'
compiler = sdk/'ndk/30.0.14904198/toolchains/llvm/prebuilt'/host/'bin'/('clang++.exe' if platform.system() == 'Windows' else 'clang++')
generated = root/'app/build/generated/directSyscallProbe'
generated.mkdir(parents=True, exist_ok=True)
source = (root/'third_party/virtualapp-upstream/lib/src/main/jni/Foundation/IORelocator64.cpp').read_text('utf8')
start = source.index('static long limbus_direct_openat(')
end = source.index('static void *allocate_limbus_branch_trampoline(', start)
(generated/'direct-wrappers.inc').write_text(source[start:end], encoding='utf8')
output = root/'app/build/generated/jniProbe/arm64-v8a/libbybus_syscall_probe.so'
output.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([str(compiler), '--target=aarch64-linux-android26', '-shared', '-fPIC', '-O2', '-std=c++17',
    '-Wall', '-Wextra', '-Werror', '-fno-exceptions', '-fno-rtti', '-nostdlib++', '-I'+str(generated),
    '-Wl,-z,max-page-size=16384', str(root/'scripts/jni-direct-syscall-probe.cpp'), '-o', str(output)], check=True)
print(output)

"""Build the production exec wrapper regression; run on a disposable emulator."""
import argparse,os,platform,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--abi',choices=['arm64-v8a','x86_64'],default='x86_64')
p.add_argument('--baseline',type=Path)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
r=Path(__file__).resolve().parent.parent;s=r/'third_party/virtualapp-upstream/lib/src/main/jni/Foundation'
text=(s/'IORelocator64.cpp').read_text('utf8')
if a.baseline:body=a.baseline.read_text('utf8')
else:body=text[text.index('static int exec_elf_class('):text.index('HOOK_DEF(void *, dlopen_CI')]
(a.output/'exec-under-test.inc').write_text(body,'utf8')
sdk=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
win=platform.system()=='Windows'; suffix='.exe' if win else ''
tools=sdk/'ndk/30.0.14904198/toolchains/llvm/prebuilt'/('windows-x86_64' if win else 'linux-x86_64')/'bin'
target='aarch64-linux-android26' if a.abi=='arm64-v8a' else 'x86_64-linux-android26'
flags=['--target='+target,'-O2','-g','-I'+str(s),'-I'+str(a.output)]
obj=a.output/'canonicalize.o'
subprocess.run([str(tools/('clang'+suffix)),*flags,'-c',str(s/'canonicalize_md.c'),'-o',str(obj)],check=True)
subprocess.run([str(tools/('clang++'+suffix)),*flags,'-std=c++17','-static-libstdc++',
    str(r/'scripts/native-exec-tests.cpp'),str(s/'SandboxFs.cpp'),str(obj),'-llog','-o',str(a.output/'exec-test')],check=True)
print(a.output/'exec-test')

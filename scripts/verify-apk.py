from pathlib import Path
import hashlib
import json
import struct
import sys
import zipfile
import lzma
import io

apk = Path(sys.argv[1])
report_path = Path(sys.argv[2])
result = {"apk": str(apk), "size_bytes": apk.stat().st_size, "sha256": hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()}

def provider_dex_contract(data):
    """Read definitions, not just string references: hidden compile stubs must stay off-device."""
    def u32(offset): return struct.unpack_from('<I', data, offset)[0]
    def leb(offset):
        value = shift = 0
        for _ in range(5):
            byte = data[offset]; offset += 1
            value |= (byte & 127) << shift
            if not byte & 128: return value, offset
            shift += 7
        raise AssertionError('Invalid DEX integer')
    strings_size, strings_offset = struct.unpack_from('<II', data, 56)
    strings = []
    for i in range(strings_size):
        _, offset = leb(u32(strings_offset + 4 * i))
        strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace'))
    type_count, type_offset = struct.unpack_from('<II', data, 64)
    types = [strings[u32(type_offset + i * 4)] for i in range(type_count)]
    class_count, class_offset = struct.unpack_from('<II', data, 96)
    methods_offset = u32(92)
    target_methods = set()
    for i in range(class_count):
        offset = class_offset + i * 32
        name = types[u32(offset)]
        assert name != 'Landroid/content/IContentProvider;', 'Compile-only platform stub was packaged'
        assert not name.startswith('Lcom/example/limbuszhcn/ProviderProbe'), 'Test provider was packaged'
        assert name != 'Lcom/example/limbuszhcn/NativeSyscallProbe;', 'Native syscall fixture was packaged'
        if name != 'Lcom/lody/virtual/client/hook/providers/RoutedContentResolver;': continue
        cursor = u32(offset + 24)
        counts = []
        for _ in range(4):
            count, cursor = leb(cursor); counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, cursor = leb(cursor); _, cursor = leb(cursor)
        for count in counts[2:]:
            method_idx = 0
            for _ in range(count):
                diff, cursor = leb(cursor); method_idx += diff
                _, cursor = leb(cursor); _, cursor = leb(cursor)
                target_methods.add(strings[u32(methods_offset + method_idx * 8 + 4)])
    return target_methods

def digest_entries(archive, names):
    digest = hashlib.sha256()
    for name in names:
        with archive.open(name) as stream:
            decoded = lzma.LZMAFile(stream) if name.endswith('.xz') else stream
            while block := decoded.read(1024 * 1024):
                digest.update(block)
    return digest.hexdigest()

with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    assert not any('libbybus_syscall_probe.so' in name for name in names), 'Native test library was packaged'
    provider_methods = set()
    for name in names:
        if name.startswith('classes') and name.endswith('.dex'):
            provider_methods.update(provider_dex_contract(archive.read(name)))
    required = {'acquireProvider', 'acquireExistingProvider', 'acquireUnstableProvider',
                'releaseProvider', 'releaseUnstableProvider', 'unstableProviderDied', 'appNotRespondingViaProvider'}
    assert required <= provider_methods, 'R8 removed/renamed hidden ContentResolver overrides'
    result['provider_contract'] = {'hidden_overrides': sorted(required), 'compile_stub_packaged': False}
    assert 'lib/arm64-v8a/libv++_64.so' in names, 'VirtualApp native library missing'
    libraries = []
    for item in archive.infolist():
        if not (item.filename.startswith('lib/') and item.filename.endswith('.so')):
            continue
        data = archive.read(item)
        assert data[:6] == b'\x7fELF\x02\x01', item.filename + ': expected ELF64 little-endian'
        assert struct.unpack_from('<H', data, 18)[0] == 183, item.filename + ': expected AArch64'
        phoff = struct.unpack_from('<Q', data, 32)[0]
        entsize, count = struct.unpack_from('<HH', data, 54)
        segments = []
        for i in range(count):
            kind, flags, offset, address, _, file_size, memory_size, align = struct.unpack_from('<IIQQQQQQ', data, phoff+i*entsize)
            if kind == 1:
                assert align >= 16384 and (address-offset) % 16384 == 0, item.filename + ': bad ELF load alignment'
                segments.append(align)
        assert segments
        with apk.open('rb') as stream:
            stream.seek(item.header_offset+26)
            filename_len, extra_len = struct.unpack('<HH', stream.read(4))
        data_offset = item.header_offset+30+filename_len+extra_len
        if item.compress_type == zipfile.ZIP_STORED:
            assert data_offset % 16384 == 0, item.filename + ': bad ZIP 16K alignment'
        libraries.append({"path": item.filename, "load_alignment": segments, "compression": item.compress_type, "data_offset": data_offset})
    result['native_libraries'] = libraries
    expected_assets = {
        'microG Services': (['assets/microg/com.google.android.gms-252432036.apk.part00','assets/microg/com.google.android.gms-252432036.apk.part01'], '1094fa60ace2367ac52bd08d1bd4f33fa8144ba930e355121b28234b8ebbf382'),
        'microG Companion': (['assets/microg/com.android.vending-84022636.apk'], '3f1bde2f72bca746fbb53b61a32c2a85fcb026f886ee96d6fe9a05de9a6c6d5b'),
        'Chinese font': (['assets/runtime/ChineseFont.ttf.xz'], 'a56a06f1af27726bc5def015b61deecbbdf5ae6d954a91b1131d8a17290def35')
    }
    result['bundled_asset_hashes'] = {}
    result['bundled_google_16k_alignment'] = {}
    native_runtime = archive.read('lib/arm64-v8a/libv++_64.so')
    font_name = 'ChineseFont-' + expected_assets['Chinese font'][1][:8] + '.ttf'
    assert font_name.encode('ascii') in native_runtime, 'Native font path does not match the bundled face'
    result['native_font_filename'] = font_name
    assert 'assets/runtime/ChineseFont.ttf' not in names, 'Uncompressed duplicate font bundled'
    for name, (paths, expected) in expected_assets.items():
        actual = digest_entries(archive, paths)
        assert actual == expected, name + ': checksum mismatch'
        result['bundled_asset_hashes'][name] = actual
        if name.startswith('microG'):
            # A host APK can be aligned while a nested component is not. Record
            # this separately; optional upstream libraries are not silently fixed.
            with zipfile.ZipFile(io.BytesIO(b''.join(archive.read(part) for part in paths))) as component:
                native = [entry for entry in component.infolist()
                          if entry.filename.startswith('lib/arm64-v8a/') and entry.filename.endswith('.so')]
                incompatible = []
                for entry in native:
                    with component.open(entry) as stream: header = stream.read(16384)
                    assert header[:6] == b'\x7fELF\x02\x01', entry.filename
                    offset = struct.unpack_from('<Q', header, 32)[0]
                    width, count = struct.unpack_from('<HH', header, 54)
                    assert width == 56 and 0 < count <= 64 and offset + count * width <= len(header)
                    loads = [struct.unpack_from('<IIQQQQQQ', header, offset + i * width) for i in range(count)]
                    if any(s[0] == 1 and (s[7] < 16384 or (s[3] - s[2]) % 16384) for s in loads):
                        incompatible.append(entry.filename)
                result['bundled_google_16k_alignment'][name] = {
                    'arm64_library_count': len(native), 'unaligned_libraries': incompatible,
                    'device_login_verified': False}
    result['license_assets'] = [name for name in names if name.startswith('assets/licenses/')]
    assert len(result['license_assets']) >= 2
    result['no_signing_secrets_packaged'] = not any('keystore.properties' in name or name.endswith(('.jks','.p12','.keystore')) for name in names)
    assert result['no_signing_secrets_packaged']

report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({"sha256": result['sha256'], "size_bytes": result['size_bytes'], "native_library_count": len(libraries), "checks": "ELF/ZIP 16K alignment, bundled hashes, license assets, no signing keys: passed"}, ensure_ascii=False))

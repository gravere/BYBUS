from pathlib import Path
import hashlib
import json
import struct
import sys
import zipfile
import lzma

apk = Path(sys.argv[1])
report_path = Path(sys.argv[2])
result = {"apk": str(apk), "size_bytes": apk.stat().st_size, "sha256": hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()}

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
        'microG Services': (['assets/microg/com.google.android.gms-252432034.apk.part00','assets/microg/com.google.android.gms-252432034.apk.part01'], 'e5e6cba078bdec812b5450599350e5c1a12c8b7438da5bf77123af170f8c1a35'),
        'microG Companion': (['assets/microg/com.android.vending-84022634.apk'], '9b8616011cc8f026bcbbe094df850794abb9d6d9f23214d7a8f8c4d1033ea012'),
        'Chinese font': (['assets/runtime/ChineseFont.ttf.xz'], 'a56a06f1af27726bc5def015b61deecbbdf5ae6d954a91b1131d8a17290def35')
    }
    result['bundled_asset_hashes'] = {}
    native_runtime = archive.read('lib/arm64-v8a/libv++_64.so')
    font_name = 'ChineseFont-' + expected_assets['Chinese font'][1][:8] + '.ttf'
    assert font_name.encode('ascii') in native_runtime, 'Native font path does not match the bundled face'
    result['native_font_filename'] = font_name
    assert 'assets/runtime/ChineseFont.ttf' not in names, 'Uncompressed duplicate font bundled'
    for name, (paths, expected) in expected_assets.items():
        actual = digest_entries(archive, paths)
        assert actual == expected, name + ': checksum mismatch'
        result['bundled_asset_hashes'][name] = actual
    result['license_assets'] = [name for name in names if name.startswith('assets/licenses/')]
    assert len(result['license_assets']) >= 2
    result['no_signing_secrets_packaged'] = not any('keystore.properties' in name or name.endswith(('.jks','.p12','.keystore')) for name in names)
    assert result['no_signing_secrets_packaged']

report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({"sha256": result['sha256'], "size_bytes": result['size_bytes'], "native_library_count": len(libraries), "checks": "ELF/ZIP 16K alignment, bundled hashes, license assets, no signing keys: passed"}, ensure_ascii=False))

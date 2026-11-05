"""Render release prose from APK metadata; keep version numbers out of maintained docs."""
import re


def section(source: str, title: str) -> str:
    headings = list(re.finditer(r'^## (.+?)\s*$', source, re.MULTILINE))
    matches = [i for i, heading in enumerate(headings) if heading[1] == title]
    if len(matches) != 1:
        raise ValueError(f'Expected one release notes section: {title}')
    index = matches[0]
    end = headings[index + 1].start() if index + 1 < len(headings) else len(source)
    content = source[headings[index].end():end].strip()
    if not content:
        raise ValueError(f'Empty release notes section: {title}')
    return content


def update_summary(source: str) -> str:
    return '\n'.join(line for line in section(source, '更新内容').splitlines()
                     if line.startswith('- '))


def render_release_notes(manifest: dict, source: str) -> str:
    version, code = manifest['versionName'], manifest['versionCode']
    if not re.fullmatch(r'v\d+(?:\.\d+){1,2}', version) or type(code) is not int or code <= 0:
        raise ValueError('Invalid APK release version')
    apk = f'BYBUS-{version}-arm64-v8a.apk'
    tag = f'{version}-build.{code}'
    url = f'https://github.com/gravere/BYBUS/releases/download/{tag}/{apk}'
    for delta in manifest.get('deltas', []):
        previous = delta['fromVersionCode']
        if type(previous) is not int or not 0 < previous < code:
            raise ValueError('Invalid delta base version')
        expected = f'BYBUS-update-{previous}-to-{code}.delta'
        if delta['name'] != expected:
            raise ValueError('Delta filename does not match APK versions')
    return f'''# BYBUS {version}

[下载完整安装包]({url})

## 更新内容

{section(source, '更新内容')}

已安装 BYBUS，可在应用内检查更新。首次安装或手动更新，下载上方完整安装包即可，`BYBUS-Android.apk` 与它相同。

`BYBUS.apk` 仅用于旧版数据迁移，首次安装不要选它。迁移前请保留旧版与数据，具体步骤见[使用说明](https://github.com/gravere/BYBUS#开始使用)。
'''

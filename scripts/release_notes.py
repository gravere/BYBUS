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
    apk = 'BYBUS-Android.apk'
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

首次安装或手动更新，只需下载上方 `BYBUS-Android.apk`。已安装 BYBUS，也可在“设置与工具 → 应用更新”中更新。

`BYBUS.apk` 是旧版数据迁移文件，不是第二个游戏安装包。仅迁移旧版数据时使用，普通安装无需下载；具体步骤见[使用说明](https://github.com/gravere/BYBUS#开始使用)。
'''

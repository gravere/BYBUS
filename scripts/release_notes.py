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
    universal = manifest.get('reuse', {}).get('format') == 'bybus-range-v1'
    update = ('支持通用增量的更新器会复用已安装 APK 中相同的内容，只下载缺少的部分，不受保留几个旧版本限制。旧更新器本次会下载完整包，升级后即可使用新机制。'
              if universal else '已安装 APK 与差异补丁匹配时优先增量下载。')
    rows = [f'| `{apk}` 或 `BYBUS-Android.apk` | 相同的独立版完整安装包，首次安装或手动更新任选一个 |',
            '| `BYBUS.apk` | 旧版迁移助手，仅供旧 BYBUS 迁移；首次安装不要选它 |']
    for delta in manifest.get('deltas', []):
        previous = delta['fromVersionCode']
        if type(previous) is not int or not 0 < previous < code:
            raise ValueError('Invalid delta base version')
        expected = f'BYBUS-update-{previous}-to-{code}.delta'
        if delta['name'] != expected:
            raise ValueError('Delta filename does not match APK versions')
        rows.append(f'| `{expected}` | 构建 {previous} → {code} 的差异补丁，由应用自动处理 |')
    rows.extend(['| `bybus-app-update.json`、`bybus-update.json`、`SHA256SUMS.txt` | 独立版与旧版迁移入口的元数据和校验值，不是安装包 |',
                 '| Source code | 项目源码，不是安装包 |'])
    return f'''# BYBUS {version}

**首次安装或手动更新，请下载完整 APK：**

[下载 BYBUS {version} · Android ARM64 完整安装包]({url})

## 更新内容

{section(source, '更新内容')}

## 如何更新

已使用独立版的用户可在应用内检查更新，或用完整 APK 覆盖安装。{update}

**旧版首次迁移：** 在原 BYBUS 内检查更新，安装迁移助手；打开助手下载独立版，在独立版选择「导入旧版数据」。导入需要额外存储空间，旧数据不会自动删除；确认独立版正常后，再移除迁移助手，即可同时安装 limbusZhCN。不要提前卸载旧版或清除数据。迁移不会重新下载已有游戏资源；Google 凭据可能需要重新授权。

首次使用请先登录并完成游戏资源下载，再返回 BYBUS 启动一次以启用完整汉化。容器内 Google 账号与系统 Play 商店独立，新设备需重新登录。

## 附件怎么选

| 文件 | 用途 |
| --- | --- |
{chr(10).join(rows)}

{'无需单独下载增量文件，所需内容由应用自动获取。旧资源变化较多时下载量会增加，不能保证所有旧版都只需几 MB。' if universal else '`.delta` 不能独立安装。'}增量更新减少下载量，合成与覆盖安装仍需完整 APK 的临时空间。发行文件保留最近三个正式构建。
'''

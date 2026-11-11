"""Inspect the released app's revised pages; no game, account or purchase actions."""
import re
import time


def inspect(env):
    adb, capture, hierarchy = (env[k] for k in ('adb', 'capture', 'hierarchy'))
    tap, locate, report = (env[k] for k in ('tap', 'locate', 'REPORT'))

    def reach(tree, label):
        for attempt in range(7):
            try:
                locate(tree, label)
                return tree
            except RuntimeError:
                scroll = next(n for n in tree.iter('node') if n.get('scrollable') == 'true')
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', scroll.get('bounds', '')))
                adb('shell', 'input', 'swipe', str((x1+x2)//2), str(y2-32), str((y1+y2)//2), '350')
                tree = hierarchy('details-scroll')
        raise RuntimeError('Cannot reach ' + label)

    def close(tree):
        tap('关闭面板', tree)
        return hierarchy('details-home')

    def about(home, name):
        tap('使用说明', home)
        tree = capture(name)
        locate(tree, '开始使用')
        tree = reach(tree, '游戏更新与数据缓存')
        # No disclosure tap: the body must already be expanded.
        body = '保留系统安装的游戏，从原渠道更新后，BYBUS 会自动同步。'
        tree = reach(tree, body)
        locate(tree, body)
        capture(name + '-body')
        return close(tree)

    home = about(capture('home'), 'about')
    tap('都市零协会', home)
    project = capture('project')
    locate(project, '翻译项目')
    labels = [n.get('text', '') for n in project.iter('node')]
    assert '边狱巴士 · 简体中文' not in labels
    assert '正式汉化包 · 启动前自动检查更新' not in labels
    tap('项目来源：翻译进度', project)
    progress = reach(hierarchy('progress'), '这些统计代表什么？')
    tap('这些统计代表什么？', progress)
    capture('progress-explanation')
    home = close(hierarchy('progress-expanded'))
    tap('运行状态', home)
    status = reach(hierarchy('runtime'), '上次启动')
    locate(status, '尚无启动记录')
    capture('runtime-status')
    home = close(status)
    tap('设置与工具', home)
    settings = capture('settings')
    tap('修复', reach(settings, '修复'))
    repair = capture('repair')
    locate(repair, '修复汉化与启动文件')
    locate(repair, '开始修复')
    # No official game on this disposable image: repair must stay disabled.
    targets = [n for n in repair.iter('node') if n.get('text') == '开始修复']
    assert targets and all(n.get('enabled') == 'false' for n in targets)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    parent = hierarchy('repair-parent')
    locate(parent, '设置与工具')
    tap('更新', reach(parent, '更新'))
    update = capture('app-update')
    tap('更新类别：汉化资源', update)
    translation = capture('translation-resources')
    assert not any(n.get('text') == '边狱巴士 · 简体中文' for n in translation.iter('node'))
    home = close(translation)
    tap('设置与工具', home)
    tap('游戏更新', hierarchy('game-updates-parent'))
    updates = capture('game-updates')
    assert not any(n.get('text') == '保留系统安装包，游戏资源继续在 BYBUS 内下载。' for n in updates.iter('node'))
    home = close(updates)
    try:
        adb('shell', 'wm', 'density', '160')
        for name, size, scale in [('tablet-portrait', '600x960', '1.0'), ('tablet-landscape', '1280x800', '1.3')]:
            adb('shell', 'wm', 'size', size)
            adb('shell', 'settings', 'put', 'system', 'font_scale', scale)
            time.sleep(2)
            home = about(capture(name), name + '-about')
            tap('设置与工具', home)
            parent = reach(hierarchy(name+'-tools'), '修复')
            tap('修复', parent)
            repair = capture(name+'-repair')
            locate(repair, '开始修复')
            close(repair)
    finally:
        adb('shell', 'wm', 'size', 'reset')
        adb('shell', 'wm', 'density', 'reset')
        adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    report['checks'].append('about, project, progress, runtime, repair, update pages and repair back navigation')
    report['checks'].append('expanded help and repair layout at phone, 600dp portrait and 1280dp landscape with 1.3 font scale')
    report['result'] = 'app detail inspection completed; no game or payment attempted'

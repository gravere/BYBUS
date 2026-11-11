"""Real bundled microG capability/settings and login navigation in a disposable AVD.

No Google account, game, product query, purchase intent or payment is used.
"""
import json
import re
import struct
import time


def inspect(api, release, verifier, windows_only=False):
    adb, out, report = api['adb'], api['OUT'], api['REPORT']
    package, namespace = api['PACKAGE'], api['NAMESPACE']
    adb('root'); adb('wait-for-device', timeout=30)
    assert adb('shell', 'id', '-u').strip() == '0', 'disposable emulator only'
    report['probe_sha256'] = api['install_component_probe'](release, verifier)
    result_path = f'/data/user/0/{package}/files/google-readiness.json'

    def probe(stage):
        adb('shell', 'rm', '-f', result_path)
        adb('shell', 'am', 'start', '-W', '-n', f'{package}/{namespace}.GoogleReadinessProbeActivity',
            '--es', 'stage', stage, '--activity-clear-top', timeout=60)
        deadline = time.monotonic() + 130
        while time.monotonic() < deadline:
            raw = adb('shell', 'cat', result_path, check=False)
            if raw.strip().startswith('{'):
                data = json.loads(raw)
                (out / f'google-{stage}.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
                assert data['result'] != 'failed', data.get('error')
                return data
            time.sleep(2)
        raise RuntimeError('Google fixture timed out: ' + stage)

    try:
        if windows_only:
            inspect_window_fixture(api)
            inspect_settings(api, probe)
            report['result'] = 'Remaining Google windows and settings inspection completed'
            return
        report['billing_capability'] = probe('billing')
        report['checks'].append('real bundled billing: disabled error 3 -> explicit settings -> supported 0; same provider persisted')
        probe('login')
        prefix = 'com.android.internal.systemui.navbar.'
        overlays = adb('shell', 'cmd', 'overlay', 'list')
        cases = [('portrait-three-button', 'threebutton', '720x1560', '0'),
                 ('portrait-gesture', 'gestural', '720x1560', '0'),
                 ('landscape-three-button', 'threebutton', '720x1280', '1'),
                 ('tablet-portrait', 'threebutton', '1200x1920', '0'),
                 ('tablet-landscape', 'threebutton', '1200x1920', '1')]
        results = []
        report['google_navigation'] = results
        for name, mode, size, rotation in cases:
            assert prefix + mode in overlays
            adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category', prefix + mode)
            adb('shell', 'wm', 'size', size)
            adb('shell', 'wm', 'user-rotation', 'lock', rotation)
            time.sleep(3)
            tree = api['capture']('google-' + name)
            controls = {}
            for key in ('back_button', 'next_button'):
                node = next(n for n in tree.iter('node') if n.get('resource-id') == 'com.google.android.gms:id/' + key)
                assert node.get('enabled') == 'true' and node.get('clickable') == 'true', key
                bounds = tuple(map(int, re.findall(r'\d+', node.get('bounds'))))
                assert bounds[3] - bounds[1] >= 94, '48dp button clipped: ' + key
                controls[key] = bounds
            png = (out / ('google-' + name + '.png')).read_bytes()
            width, height = struct.unpack('>II', png[16:24])
            windows = adb('shell', 'dumpsys', 'window', 'windows')
            (out / ('google-' + name + '-window.txt')).write_text(windows, encoding='utf-8')
            nav = api['visible_navigation_frames'](windows, width, height)
            assert nav, 'no real navigation bounds'
            for bounds in controls.values():
                assert 0 <= bounds[0] < bounds[2] <= width and 0 <= bounds[1] < bounds[3] <= height
                assert not any(bounds[0] < n[2] and bounds[2] > n[0] and bounds[1] < n[3] and bounds[3] > n[1] for n in nav), 'login button overlaps system navigation'
            results.append(dict(case=name, controls=controls, navigation=nav, size=[width, height]))
        # Test the actual bottom-left button, not an ADB BACK substitute.
        b = results[-1]['controls']['back_button']
        adb('shell', 'input', 'tap', str((b[0]+b[2])//2), str((b[1]+b[3])//2))
        time.sleep(2)
        tree = api['hierarchy']('google-back-clicked')
        assert not any(n.get('resource-id') == 'com.google.android.gms:id/next_button' for n in tree.iter('node')), 'bottom-left button did not leave login'
        report['google_navigation'] = results
        report['checks'].append('real login buttons avoid gesture/three-button navigation on phone/tablet portrait/landscape; bottom-left click delivered')
        inspect_window_fixture(api)
        inspect_settings(api, probe)
        report['payment_runtime'] = 'real service capability/configuration verified; accounts, product prices and checkout not exercised'
        report['result'] = 'Google configuration and navigation inspection completed'
    finally:
        adb('shell', 'wm', 'size', 'reset', check=False)
        adb('shell', 'wm', 'user-rotation', 'free', check=False)
        (out / 'google-readiness-log.txt').write_text(adb('logcat', '-d', '-v', 'threadtime', '-t', '2500', check=False), encoding='utf-8')


def inspect_settings(api, probe):
    adb = api['adb']
    # Native microG settings retains its own fitsSystemWindows; capture for visual review.
    adb('shell', 'wm', 'size', '720x1560'); adb('shell', 'wm', 'user-rotation', 'lock', '0')
    probe('settings'); time.sleep(2)
    api['capture']('google-native-settings')
    adb('shell', 'am', 'force-stop', api['PACKAGE'])
    adb('shell', 'am', 'start', '-W', '-n', f"{api['PACKAGE']}/{api['NAMESPACE']}.MainActivity", timeout=60)
    time.sleep(3)
    api['tap']('设置与工具', api['hierarchy']('payment-home'))
    api['tap']('Google 账号与付款', api['hierarchy']('payment-tools'))
    time.sleep(3)
    tree = api['capture']('payment-preparation-page')
    if any(n.get('text') == '启用付款服务' for n in tree.iter('node')):
        api['tap']('启用付款服务', tree)
        time.sleep(3)
        api['locate'](api['capture']('payment-enabled-page'), '已启用')
        api['REPORT']['checks'].append('host payment setup button saved the actual enabled state')
    api['tap']('关闭面板', api['hierarchy']('payment-panel-close'))
    api['inspect_system_navigation']()


def inspect_window_fixture(api):
    """Insets geometry only: same window flags as Companion, no simulated purchase result."""
    adb, report, out = api['adb'], api['REPORT'], api['OUT']
    adb('shell', 'wm', 'size', '720x1560')
    adb('shell', 'wm', 'user-rotation', 'lock', '0')
    adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category', 'com.android.internal.systemui.navbar.threebutton')
    adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
    cases = []
    for sheet in (True, False):
        name = 'sheet' if sheet else 'web'
        adb('shell', 'am', 'start', '-W', '-n', f"{api['PACKAGE']}/{api['NAMESPACE']}.GoogleWindowProbeActivity",
            '--ez', 'sheet', str(sheet).lower(), '--activity-clear-top', timeout=30)
        time.sleep(2)
        for keyboard in (False, True):
            if keyboard:
                api['tap']('输入测试', api['hierarchy']('window-input'))
                time.sleep(2)
            stem = 'google-window-' + name + ('-ime' if keyboard else '')
            tree = api['capture'](stem)
            button = api['clickable_bounds'](tree, '窗口操作')
            assert button[3]-button[1] >= 94, 'fixture action clipped'
            windows = adb('shell', 'dumpsys', 'window', 'windows')
            (out / (stem+'-window.txt')).write_text(windows, encoding='utf-8')
            areas = api['visible_navigation_frames'](windows, 720, 1560)
            assert areas, 'window fixture has no navigation area'
            if keyboard:
                ime = []
                for block in re.split(r'(?m)^  Window #\d+ ', windows):
                    if 'ty=INPUT_METHOD ' in block and re.search(r'(?m)^\s+isVisible=true\s*$', block):
                        match = re.search(r'Frames:.*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', block)
                        if match: ime.append(tuple(map(int, match.groups())))
                assert ime, 'keyboard was not actually shown'
                areas.extend(ime)
            assert not any(button[0] < n[2] and button[2] > n[0] and button[1] < n[3] and button[3] > n[1] for n in areas), 'fixture button overlaps keyboard/navigation'
            cases.append(dict(window=name, keyboard=keyboard, button=button, excluded=areas))
        adb('shell', 'input', 'keyevent', 'BACK')
        time.sleep(1)
        api['tap']('窗口操作', api['hierarchy']('window-keyboard-closed'))
        api['locate'](api['hierarchy']('window-action-delivered'), '操作已收到')
        adb('shell', 'input', 'keyevent', 'BACK')
    report['window_geometry_fixture'] = cases

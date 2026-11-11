"""Real bundled microG capability/settings and login navigation in a disposable AVD.

No Google account, game, product query, purchase intent or payment is used.
"""
import json
import re
import struct
import time


def redact_checkin(text):
    """Cloud emulator IDs are also unnecessary in downloadable test evidence."""
    text = re.sub(r'(?i)(Checked in as\s+)[0-9a-f]+', r'\1[redacted]', text)
    return re.sub(r'(?i)((?:android[_ ]?id|security[_ ]?token)\s*[=:]\s*)[0-9a-fx-]+', r'\1[redacted]', text)


def inspect(api, release, verifier, windows_only=False, settings_only=False):
    adb, out, report = api['adb'], api['OUT'], api['REPORT']
    package, namespace = api['PACKAGE'], api['NAMESPACE']
    # adbd can close the transport while restarting as root; verify the resulting UID.
    for attempt in range(3):
        adb('root', check=False)
        adb('wait-for-device', timeout=30)
        if adb('shell', 'id', '-u', check=False).strip() == '0': break
        time.sleep(2)
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
        report['billing_capability'] = probe('billing')
        assert report['billing_capability']['device_registered'] is True
        report['checks'].append('first real Google check-in completed before billing capability; provider ID and success time both present (values not exported)')
        report['checks'].append('real billing: fresh defaults supported 0; old disabled error 3 -> automatic repair -> supported 0; same provider persisted')
        if windows_only:
            if not settings_only: inspect_window_fixture(api)
            inspect_settings(api, probe)
            report['result'] = 'Remaining Google windows and settings inspection completed'
            return
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
        (out / 'google-readiness-log.txt').write_text(redact_checkin(adb('logcat', '-d', '-v', 'threadtime', '-t', '2500', check=False)), encoding='utf-8')


def inspect_settings(api, probe):
    adb = api['adb']
    # Native microG settings retains its own fitsSystemWindows; capture for visual review.
    adb('shell', 'wm', 'size', '720x1560'); adb('shell', 'wm', 'user-rotation', 'lock', '0')
    frozen_pid = None
    if api['REPORT']['profile'] == 'android17':
        # Fresh fixture: the first guest process belongs to SettingsProvider (GMS).
        # Only this disposable AVD is controlled; no system-wide freezer setting changes.
        process = api['PACKAGE'] + ':p0'
        frozen_pid = process_record_pid(adb, process)
        group = adb('shell', 'cat', f'/proc/{frozen_pid}/cgroup')
        match = re.search(r'(?m)^0::(/[^\r\n]*)$', group)
        assert match and '..' not in match[1], 'unified process cgroup unavailable'
        events_path = '/sys/fs/cgroup' + match[1] + '/cgroup.events'
        adb('shell', 'am', 'freeze', process)
        for _ in range(20):
            events = adb('shell', 'cat', events_path)
            if re.search(r'(?m)^frozen 1$', events): break
            time.sleep(.25)
        assert re.search(r'(?m)^frozen 1$', events), 'Google owner was not actually frozen'
        api['REPORT']['settings_owner_before'] = 'kernel cgroup frozen=1'
    probe('settings'); time.sleep(2)
    if frozen_pid:
        assert process_record_pid(adb, api['PACKAGE'] + ':p0') == frozen_pid, 'Google owner was killed instead of resumed'
        api['REPORT']['checks'].append('frozen Google settings owner resumed through scoped system provider connection; same process survived')
        api['REPORT']['settings_owner_resumed'] = True
    api['capture']('google-native-settings')
    adb('shell', 'am', 'force-stop', api['PACKAGE'])
    adb('shell', 'am', 'start', '-W', '-n', f"{api['PACKAGE']}/{api['NAMESPACE']}.MainActivity", timeout=60)
    time.sleep(3)
    api['tap']('设置与工具', api['hierarchy']('payment-home'))
    api['tap']('Google 账号与付款', api['hierarchy']('payment-tools'))
    time.sleep(3)
    tree = api['capture']('payment-preparation-page')
    api['locate'](tree, '已启用')
    assert not any(n.get('text') == '启用付款服务' for n in tree.iter('node')), 'automatic defaults were lost'
    api['REPORT']['checks'].append('host payment page shows service enabled without a manual switch')
    api['tap']('关闭面板', api['hierarchy']('payment-panel-close'))
    api['inspect_system_navigation']()


def process_record_pid(adb, process):
    # VirtualApp changes Linux argv[0]; use Android's owning ProcessRecord, not pidof.
    records = adb('shell', 'dumpsys', 'activity', 'processes')
    pids = set(re.findall(r'ProcessRecord\{[^{}\s]+\s+(\d+):' + re.escape(process) + r'/[^}]+\}', records))
    assert len(pids) == 1, 'fixture Google owner missing or ambiguous'
    return pids.pop()


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
                ime = visible_ime_frames(windows)
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


def visible_ime_frames(windows):
    areas = []
    for block in re.split(r'(?m)^  Window #\d+ ', windows):
        if 'ty=INPUT_METHOD ' not in block or not re.search(r'(?m)^\s+isVisible=true\s*$', block):
            continue
        frame = re.search(r'Frames:.*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', block)
        content = re.search(r'mGivenContentInsets=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', block)
        assert frame and content, 'keyboard bounds unavailable'
        x1, y1, x2, y2 = map(int, frame.groups())
        left, top, right, bottom = map(int, content.groups())
        # The IME surface includes transparent space above the actual keyboard.
        bounds = (x1+left, y1+top, x2-right, y2-bottom)
        assert bounds[0] < bounds[2] and bounds[1] < bounds[3]
        areas.append(bounds)
    return areas

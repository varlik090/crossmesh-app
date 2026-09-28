# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_submodules

hiddenimports = []
hiddenimports += collect_submodules('paho')
hiddenimports += collect_submodules('cryptography')
hiddenimports += ['socks']

a = Analysis(
    ['CYPHER_NET.py'], pathex=[], binaries=[], datas=[], hiddenimports=hiddenimports,
    hookspath=[], hooksconfig={}, runtime_hooks=[], excludes=[], noarchive=False, optimize=1,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas, [], name='CYPHER_NET', debug=False,
    bootloader_ignore_signals=False, strip=False, upx=False, console=False,
    disable_windowed_traceback=False, argv_emulation=False, target_arch=None,
    codesign_identity=None, entitlements_file=None, icon='CYPHER_NET.ico', version='version_info.txt',
)

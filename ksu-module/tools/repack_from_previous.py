#!/usr/bin/env python3
"""用上一版交付包里的 ROM 产物 + 当前模块脚本重新打包（Linux / macOS 可用，不需要 ROM 与 PowerShell）。

适用场景：只改了 packaging/ 下的脚本、webroot 或 module.prop，ROM 资源没变。
ROM 生成的部分（mibackscreen_resources/、appcard_entries/、appcard_appInfo_patch.json）从上一版包里
逐字节搬过来；其余按 build_personalization_module.ps1 的规则重新生成：
  - 顶层脚本与 webroot 统一成「UTF-8 无 BOM + LF」，末尾恰好一个换行；
  - META-INF 的 Magisk 安装器原文直接从 build_personalization_module.ps1 里读，保证同一份来源；
  - *.sh 与 update-binary 在 zip 里是 0755，其余 0644。
打完后按 validate_personalization_module.ps1 的规则做校验（ROM 哈希一项改为「与上一版包逐字节一致」）。

ROM 有更新时仍要在 Windows 上用 build_personalization_module.ps1 从 ROM 构建。

用法（在仓库根目录）：
  python3 ksu-module/tools/repack_from_previous.py --base ksu-module/out/MiBackscreen_v1.3.zip
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

TOOLS = os.path.dirname(os.path.abspath(__file__))
KSU = os.path.dirname(TOOLS)
PACKAGING = os.path.join(KSU, "packaging", "mibackscreen_18_personalization")
BUILD_PS1 = os.path.join(TOOLS, "build_personalization_module.ps1")
MODULE_ID = "mibackscreen_18_personalization"
DATA_ROOT = "/data/system/theme_magic/users/0/mibackscreen_18"

# 与 build_personalization_module.ps1 的复制清单一致
TOP_LEVEL = [
    "module.prop", "skip_mount", "customize.sh", "post-fs-data.sh", "service.sh", "uninstall.sh",
    "appinfo_utils.sh", "appinfo_txn.sh", "lib.sh", "status.sh", "action.sh", "logpack.sh",
    "fix-perms.sh", "fix-regperm.sh", "clear-reareye.sh", "fix-aiapp.sh",
]
WEBROOT = ["index.html", "app.js", "style.css"]
UPDATE_BINARY = "META-INF/com/google/android/update-binary"
UPDATER_SCRIPT = "META-INF/com/google/android/updater-script"
# 从上一版包里原样保留的（ROM 产物）
ROM_DERIVED = re.compile(r"^(mibackscreen_resources/|appcard_entries/|appcard_appInfo_patch\.json$)")


def fail(msg):
    sys.exit("FAIL: " + msg)


def normalize_text(raw: bytes) -> bytes:
    """等价于 ps1 的 Write-Utf8Lf：去 BOM、CRLF/CR→LF、去掉末尾空白后补一个 \\n。"""
    text = raw.decode("utf-8-sig")
    text = text.replace("\r\n", "\n").replace("\r", "\n").rstrip() + "\n"
    return text.encode("utf-8")


def magisk_installer_from_ps1():
    src = open(BUILD_PS1, encoding="utf-8-sig").read()
    m = re.search(r"\$magiskUpdateBinary = @'\r?\n(.*?)\r?\n'@", src, re.S)
    if not m:
        fail("update-binary here-string not found in build_personalization_module.ps1")
    m2 = re.search(r'\$magiskUpdaterScript = "([^"]*)"', src)
    if not m2:
        fail("updater-script value not found in build_personalization_module.ps1")
    return normalize_text(m.group(1).encode()), normalize_text(m2.group(1).encode())


def read_prop(data: bytes):
    props = {}
    for line in data.decode("utf-8").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def mode_of(name):
    return 0o755 if name.endswith(".sh") or name == UPDATE_BINARY else 0o644


def build(base_path, out_dir):
    prop_src = open(os.path.join(PACKAGING, "module.prop"), "rb").read()
    props = read_prop(normalize_text(prop_src))
    version = props.get("version") or fail("module.prop is missing version")
    out_path = os.path.join(out_dir, "MiBackscreen_v%s.zip" % version)
    if os.path.abspath(out_path) == os.path.abspath(base_path):
        fail("output would overwrite the base package; bump version in module.prop first")

    files = set(os.listdir(PACKAGING)) - {"webroot"}
    unknown = sorted(files - set(TOP_LEVEL))
    if unknown:
        fail("packaging has files not in the copy list (update both build scripts): %s" % unknown)

    generated = {}
    for name in TOP_LEVEL:
        generated[name] = normalize_text(open(os.path.join(PACKAGING, name), "rb").read())
    for name in WEBROOT:
        generated["webroot/" + name] = normalize_text(open(os.path.join(PACKAGING, "webroot", name), "rb").read())
    binary, script = magisk_installer_from_ps1()
    generated[UPDATE_BINARY] = binary
    generated[UPDATER_SCRIPT] = script

    os.makedirs(out_dir, exist_ok=True)
    tmp = out_path + ".tmp"
    kept = 0
    with zipfile.ZipFile(base_path) as base, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for name in sorted(generated):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (0o100000 | mode_of(name)) << 16
            out.writestr(info, generated[name])
        for info in base.infolist():
            if info.is_dir() or not ROM_DERIVED.match(info.filename):
                continue
            new = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new.compress_type = zipfile.ZIP_DEFLATED
            new.create_system = 3
            new.external_attr = (0o100000 | mode_of(info.filename)) << 16
            out.writestr(new, base.read(info))
            kept += 1
    os.replace(tmp, out_path)
    return out_path, kept, len(generated)


def check_no_bom_cr(name, data):
    if data.startswith(b"\xef\xbb\xbf"):
        fail("%s starts with a UTF-8 BOM" % name)
    if b"\r" in data:
        fail("%s contains CR" % name)


def data_path_to_entry(path):
    if not path.startswith(DATA_ROOT + "/"):
        fail("unexpected data path: %s" % path)
    suffix = path[len(DATA_ROOT) + 1:]
    if suffix.startswith("appcard/"):
        return "mibackscreen_resources/media/rearscreen/" + suffix
    if suffix.startswith("precust_theme/theme/.data/"):
        return "mibackscreen_resources/" + suffix
    fail("unsupported data path: %s" % path)


def validate(zip_path, base_path):
    with zipfile.ZipFile(zip_path) as z, zipfile.ZipFile(base_path) as base:
        names = z.namelist()
        if len(names) != len(set(names)):
            fail("duplicate zip entries")
        if any(re.match(r"^(system|product)(/|$)", n) for n in names):
            fail("zip contains a mountable system or product root")
        required = [UPDATE_BINARY, UPDATER_SCRIPT] + TOP_LEVEL + ["webroot/" + w for w in WEBROOT] + [
            "appcard_appInfo_patch.json",
            "mibackscreen_resources/media/rearscreen/template/normal/rearScreen.json",
            "mibackscreen_resources/media/rearscreen/template/default/rearScreen.json",
        ]
        for n in required:
            if n not in names:
                fail("required zip entry is missing: %s" % n)
        for n in TOP_LEVEL:
            if not n.endswith(".sh"):
                continue
            data = z.read(n)
            check_no_bom_cr(n, data)
            if not data.startswith(b"#!/system/bin/sh\n"):
                fail("%s has an invalid shebang or line ending" % n)
            if (z.getinfo(n).external_attr >> 16) & 0o777 != 0o755:
                fail("%s mode is not 0755" % n)
        for n in (UPDATE_BINARY, UPDATER_SCRIPT, "module.prop") + tuple("webroot/" + w for w in WEBROOT):
            check_no_bom_cr(n, z.read(n))
        ub = z.read(UPDATE_BINARY).decode()
        if not ub.startswith("#!/sbin/sh\n") or ". /data/adb/magisk/util_functions.sh" not in ub \
                or "install_module" not in ub:
            fail("update-binary is not the Magisk module_installer script")
        if (z.getinfo(UPDATE_BINARY).external_attr >> 16) & 0o777 != 0o755:
            fail("update-binary mode is not 0755")
        if z.read(UPDATER_SCRIPT).decode().strip() != "#MAGISK":
            fail("updater-script must contain only #MAGISK")

        prop = read_prop(z.read("module.prop"))
        src = read_prop(normalize_text(open(os.path.join(PACKAGING, "module.prop"), "rb").read()))
        if prop.get("id") != MODULE_ID:
            fail("module.prop identity is invalid: %s" % prop.get("id"))
        for key in ("version", "versionCode"):
            if prop.get(key) != src.get(key):
                fail("module.prop %s mismatch: %s vs source %s" % (key, prop.get(key), src.get(key)))
        if not re.fullmatch(r"\d+", prop.get("versionCode", "")):
            fail("module.prop versionCode is not an integer")
        base_prop = read_prop(base.read("module.prop"))
        if int(prop["versionCode"]) <= int(base_prop.get("versionCode", "0")):
            fail("versionCode %s is not greater than the base package's %s"
                 % (prop["versionCode"], base_prop.get("versionCode")))

        for n in names:
            if n.endswith(".json"):
                try:
                    json.loads(z.read(n).decode("utf-8-sig"))
                except Exception as e:  # noqa: BLE001
                    fail("invalid JSON %s: %s" % (n, e))

        entries = [n for n in names if n.startswith("appcard_entries/") and n.endswith(".json")]
        if len(entries) != 6:
            fail("expected 6 app card entries, got %d" % len(entries))
        ids = set()
        for n in entries:
            card = json.loads(z.read(n).decode("utf-8-sig"))
            ids.add(card["resId"])
            for key in ("mtzPath", "appIconPath", "previewLightPath", "previewDarkPath"):
                if card.get(key):
                    entry = data_path_to_entry(card[key])
                    if entry not in names:
                        fail("%s references a resource missing from zip: %s" % (n, entry))
        if len(ids) != 6:
            fail("app card IDs are not unique")

        # ROM 产物必须与上一版包逐字节一致
        base_rom = {i.filename for i in base.infolist() if not i.is_dir() and ROM_DERIVED.match(i.filename)}
        new_rom = {n for n in names if ROM_DERIVED.match(n)}
        if base_rom != new_rom:
            fail("ROM-derived entries differ from base: %s" % sorted(base_rom ^ new_rom)[:5])
        for n in base_rom:
            if hashlib.sha256(z.read(n)).digest() != hashlib.sha256(base.read(n)).digest():
                fail("ROM-derived entry changed: %s" % n)
        return prop, len(names), len(base_rom)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="上一版交付包（提供 ROM 产物）")
    ap.add_argument("--out-dir", default=os.path.join(KSU, "out"), help="输出目录，默认 ksu-module/out")
    args = ap.parse_args()
    out_path, kept, generated = build(args.base, args.out_dir)
    prop, total, rom = validate(out_path, args.base)
    sha = hashlib.sha256(open(out_path, "rb").read()).hexdigest()
    print("zip      %s" % out_path)
    print("version  %s (versionCode %s)" % (prop["version"], prop["versionCode"]))
    print("entries  %d（脚本 / webroot / META-INF 重新生成 %d，ROM 产物沿用 %d）" % (total, generated, rom))
    print("bytes    %d" % os.path.getsize(out_path))
    print("sha256   %s" % sha)
    print("validate OK")


if __name__ == "__main__":
    main()

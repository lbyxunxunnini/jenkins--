#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import xml.etree.ElementTree as ET
import os
import sys

manifest_path = "android/app/src/main/AndroidManifest.xml"

if not os.path.exists(manifest_path):
    print(f"❌ 文件不存在: {manifest_path}")
    sys.exit(1)

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# 保留原有命名空间
ET.register_namespace('android', ANDROID_NS)
ET.register_namespace('tools', "http://schemas.android.com/tools")

tree = ET.parse(manifest_path)
root = tree.getroot()

application = root.find('application')
if application is None:
    print("❌ 找不到 <application> 节点")
    sys.exit(1)

inserted = False

# 遍历 <meta-data> 节点并打印
print("🔹 当前 <meta-data> 节点列表（插入前）：")
for md in application.findall('meta-data'):
    name = md.attrib.get(f'{{{ANDROID_NS}}}name')
    value = md.attrib.get(f'{{{ANDROID_NS}}}value')
    print(f"  - android:name='{name}', android:value='{value}'")

# 插入 Impeller
for md in application.findall('meta-data'):
    name = md.attrib.get(f'{{{ANDROID_NS}}}name')
    value = md.attrib.get(f'{{{ANDROID_NS}}}value')

    if name == 'wechat_kit_main_activity' and value == 'tech.ycyx.yinchao.MainActivity':
        # 检查是否已存在
        exists = any(
            m.attrib.get(f'{{{ANDROID_NS}}}name') == 'io.flutter.embedding.android.EnableImpeller'
            for m in application.findall('meta-data')
        )
        if not exists:
            impeller = ET.Element('meta-data')
            impeller.set(f'{{{ANDROID_NS}}}name', 'io.flutter.embedding.android.EnableImpeller')
            impeller.set(f'{{{ANDROID_NS}}}value', 'false')
            # 插入在当前节点之后
            idx = list(application).index(md)
            application.insert(idx + 1, impeller)
            inserted = True
        break

# 保存 XML
tree.write(manifest_path, encoding='utf-8', xml_declaration=True)

# 输出插入状态
if inserted:
    print("✅ 已成功插入禁用 Impeller 配置")
else:
    print("ℹ️ Impeller 配置已存在或未找到目标 meta-data")

# 再次打印 <meta-data> 节点列表
print("🔹 当前 <meta-data> 节点列表（插入后）：")
for md in application.findall('meta-data'):
    name = md.attrib.get(f'{{{ANDROID_NS}}}name')
    value = md.attrib.get(f'{{{ANDROID_NS}}}value')
    print(f"  - android:name='{name}', android:value='{value}'")

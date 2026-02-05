#!/bin/bash
set -e

# 参数：APK 输出目录、BUILD_NAME、ANDROID_BUILD_NUMBER 1
APK_OUTPUT_PATH="$1"
BUILD_NAME="$2"
ANDROID_BUILD_NUMBER="$3"

if [ -z "$APK_OUTPUT_PATH" ] || [ -z "$BUILD_NAME" ] || [ -z "$ANDROID_BUILD_NUMBER" ]; then
    echo "Usage: $0 <APK_OUTPUT_PATH> <BUILD_NAME> <ANDROID_BUILD_NUMBER>"
    exit 1
fi

cd "$APK_OUTPUT_PATH"

# 找到最新生成的 zip 文件
zip_file=$(ls -t *.zip | head -n1)
echo "📦 解压文件: $zip_file 到 sign_apk"

# 删除旧的 sign_apk 文件夹
rm -rf sign_apk
mkdir -p sign_apk

# 解压 zip 到 sign_apk
unzip -q "$zip_file" -d sign_apk


# 删除原始 zip 压缩包
echo "🗑 删除原始压缩包: $zip_file"
rm -f "$zip_file"
# 进入 sign_apk 目录
cd sign_apk

# 遍历解压后的 APK，按渠道拆分并重命名
for apk in *.apk; do
    # 提取 _sec_ 和 _sign.apk 之间的渠道名
    channel=$(echo "$apk" | sed -n 's/.*_sec_\([a-zA-Z0-9_-]*\)_sign\.apk/\1/p')
    if [ -n "$channel" ]; then
        mkdir -p "$channel"
        mv "$apk" "$channel/yinchao-v${BUILD_NAME}-${ANDROID_BUILD_NUMBER}-${channel}.apk"
        echo "✅ $apk -> $channel/yinchao-v${BUILD_NAME}-${ANDROID_BUILD_NUMBER}-${channel}.apk"
    fi
done

echo "🎉 APK 拆分和重命名完成"

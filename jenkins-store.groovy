pipeline {
    agent any

    environment {
        // ===================== 基础版本信息 =====================
        BUILD_NAME             = "${env.BUILD_NAME ?: '1.0.0'}"
        IOS_BUILD_NUMBER       = "${env.IOS_BUILD_NUMBER ?: '1'}"
        ANDROID_BUILD_NUMBER   = "${env.ANDROID_BUILD_NUMBER ?: '1'}"

        // ===================== 路径与配置 =====================
        EXPORT_PATH            = "../pack_store"
        EXPORT_OPTIONS_PLIST_PATH = "${EXPORT_PATH}/export_options_appStore.plist"
        DART_DEFINE_FILE       = "${EXPORT_PATH}/prod.env"
        OUTPUT_DIR             = "/Users/agi00107/Desktop/outpackage/market_release"
        ARCHIVE_OUTPUT_PATH    = "${OUTPUT_DIR}/${BUILD_NAME}/ios"
        APK_OUTPUT_PATH        = "${OUTPUT_DIR}/${BUILD_NAME}/apk"

        // ===================== Git 仓库 =====================
        GIT_REPO               = 'git@codeup.aliyun.com:6710bdc09d3c82efe37b13cc/facesong/facesong_flutter.git'
        GIT_CREDENTIAL_ID      = 'git-ssh-key'
        GIT_REF                = "${env.GIT_REF ?: 'main'}"

        // ===================== APK 加固配置 =====================
        PROTECT_APK            = "${env.PROTECT_APK ?: 'true'}"
        SECAPI_JAR_PATH        = "${EXPORT_PATH}/secapi-4.1.5-SNAPSHOT.jar"
        CHANNEL_FILE           = "${EXPORT_PATH}/channelexname.txt" 

        // ===================== 钉钉告警 =====================
        DINGTALK_WEBHOOK       = "https://oapi.dingtalk.com/robot/send?access_token=ae7a01ed25859f3e9f995717eacbb5bd67dde43cbcb889c27a5868aad347016a"
        // DINGTALK_WEBHOOK       = "https://oapi.dingtalk.com/robot/send?access_token=057c702cdb1896282659cd07439846fd07ec052cf599883260c08f289f2cd89f"
    }

    stages {
        stage('初始化仓库') {
            steps {
                dir('facesong_flutter') {
                    echo "🚀 [1/7] 初始化 Git 仓库..."
                    script {
                        def gitDirExists = fileExists('.git')
                        if (!gitDirExists) {
                            deleteDir()
                            sh "git clone ${env.GIT_REPO} . && git checkout ${env.GIT_REF}"
                        } else {
                            sh "git reset --hard && git clean -fd"
                        }

                        checkout([$class: 'GitSCM',
                            branches: [[name: "${env.GIT_REF}"]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [[$class: 'CleanBeforeCheckout']],
                            userRemoteConfigs: [[
                                url: env.GIT_REPO,
                                credentialsId: env.GIT_CREDENTIAL_ID
                            ]]
                        ])
                        sh "git log -5 --pretty=format:'%h %an %ad %s' --date=short"
                    }
                }
            }
        }

        stage('确认版本号') {
            steps {
                echo "🔢 [2/7] 版本号确认：BUILD_NAME=${BUILD_NAME}, IOS_BUILD_NUMBER=${IOS_BUILD_NUMBER}, ANDROID_BUILD_NUMBER=${ANDROID_BUILD_NUMBER}"
            }
        }

        stage('Flutter 初始化') {
            steps {
                dir('facesong_flutter') {
                    sh """
                        set -e
                        rm -f pubspec.lock ios/Podfile.lock
                        fvm flutter clean
                        export PUB_HOSTED_URL=https://pub.flutter-io.cn
                        fvm flutter pub get
                    """
                }
            }
        }

        stage('构建 iOS IPA') {
            when { expression { return env.BUILD_IOS == "true" } }
            steps {
                dir('facesong_flutter') {
                    script {
                        def iosBuildResult = sh(
                            script: """
                                set -e
                                sh build.sh ipa \
                                    --channel AppStore \
                                    --flavor production \
                                    --export-options-plist=${EXPORT_OPTIONS_PLIST_PATH} \
                                    --dart-define-from-file=\${DART_DEFINE_FILE} \
                                    --dart-define=WATERMARK=false \
                                    --dart-define=DEV_CONFIG=false \
                                    --build-name ${BUILD_NAME} \
                                    --build-number ${IOS_BUILD_NUMBER}
                            """,
                            returnStatus: true
                        )

                        if (iosBuildResult == 0) {
                            sendDingTalkMessage(
                                "iOS 打包完成",
                                "### ✅ iOS 打包完成\n- 版本: ${BUILD_NAME} (${IOS_BUILD_NUMBER})\n- 产物路径: [smb://10.200.35.17](smb://10.200.35.17)"
                            )
                        } else {
                            sendDingTalkMessage(
                                "iOS 打包失败",
                                "### ❌ iOS 打包失败\n- 版本: ${BUILD_NAME} (${IOS_BUILD_NUMBER})"
                            )
                            error("iOS 构建失败")
                        }
                    }
                }
            }
        }

        stage('复制 xcarchive') {
            when { expression { return env.BUILD_IOS == "true" } }
            steps {
                dir('facesong_flutter') {
                    script {
                        def sourceDir = "build/ios/archive/Runner.xcarchive"
                        def targetDir = "${ARCHIVE_OUTPUT_PATH}/Runner.xcarchive"
                        sh """
                            set -e
                            mkdir -p "${ARCHIVE_OUTPUT_PATH}"
                            if [ -d "${sourceDir}" ]; then
                                rm -rf "${targetDir}"
                                cp -R "${sourceDir}" "${targetDir}"
                            else
                                exit 1
                            fi
                        """
                    }
                }
            }
        }

        stage('构建 & 加固 Android APK') {
            when { expression { return env.BUILD_ANDROID == "true" } }
            steps {
                dir('facesong_flutter') {
                    script {
                        sh "mkdir -p ${APK_OUTPUT_PATH}"

                        sh """
                            set -e
                            cp "${EXPORT_PATH}/key.properties" android/app/key.properties
                            cp "${EXPORT_PATH}/release.keystore" android/app/release.keystore
                        """

                        echo "🟢 开始构建 APK"

                        def buildResult = sh(
                            script: """
                                set -e
                                if sed --version >/dev/null 2>&1; then
                                  sed -i 's/minSdk = flutter\\.minSdkVersion/minSdk = 24/' android/app/build.gradle
                                else
                                  sed -i '' 's/minSdk = flutter\\.minSdkVersion/minSdk = 24/' android/app/build.gradle
                                fi

                                fvm flutter build apk \
                                    --flavor production \
                                    --release \
                                    --dart-define-from-file="\${DART_DEFINE_FILE}" \
                                    --dart-define=WATERMARK=false \
                                    --dart-define=DEV_CONFIG=false \
                                    --build-name="${BUILD_NAME}" \
                                    --build-number="${ANDROID_BUILD_NUMBER}"
                            """,
                            returnStatus: true
                        )

                        if (buildResult != 0) {
                            sendDingTalkMessage(
                                "Android 构建失败",
                                "### ❌ Android 构建失败\n- 版本: ${BUILD_NAME} (${ANDROID_BUILD_NUMBER})"
                            )
                            error("❌ APK 构建失败")
                        }

                        def builtApk = "build/app/outputs/flutter-apk/app-production-release.apk"
                        if (!fileExists(builtApk)) {
                            error("❌ 未找到 APK 文件: ${builtApk}")
                        }
                        sh "echo '渠道文件内容 (Shell):' && cat ${CHANNEL_FILE}"
                        if (env.PROTECT_APK == "true") {
                            echo "🔒 开始加固 APK"
                            def protectResult = sh(
                                script: """
                                    set -e
                                    java -jar "${SECAPI_JAR_PATH}" \
                                        -i 10.200.18.111:8000 \
                                        -u zyljsh \
                                        -a 3e41fc10-8c1b-44c2-9c1c-d3a99b1330ca \
                                        -c ba2749da-6086-41cd-b801-ee75727c4bdd \
                                        -f 0 -t 100000 \
                                        -p "${builtApk}" -d "${APK_OUTPUT_PATH}" \
                                        --action ud --ks 1 -l "${CHANNEL_FILE}"
                                """,
                                returnStatus: true
                            )

                            if (protectResult != 0) {
                                sendDingTalkMessage(
                                    "Android 加固失败",
                                    "### ❌ Android APK 加固失败\n- 版本: ${BUILD_NAME} (${ANDROID_BUILD_NUMBER})"
                                )
                                error("❌ APK 加固失败")
                            }
                            echo "✅ APK 加固成功"
                        } else {
                            echo "⚙️ 未开启加固，直接复制构建产物"
                            sh "cp -v ${builtApk} ${APK_OUTPUT_PATH}/app-production-release.apk"
                        }

                        // ---------- 4️⃣ 压缩并解压 ----------
                        echo "📦 打包 APK 输出目录为 zip 并解压..."
                        sh """
                            cd ${APK_OUTPUT_PATH}
                            zip -r app_package.zip .  # 压缩当前 APK 输出目录所有文件
                            rm -rf sign_apk           # 删除旧的 sign_apk 文件夹（如果存在）
                            unzip -q app_package.zip -d sign_apk
                        """
                        echo "✅ zip 解压完成，产物文件夹命名为 sign_apk"

                        // ---------- 5️⃣ 按渠道拆分并重命名 ----------
                        echo "📂 根据 APK 名称拆分渠道文件夹并重命名..."
                        sh """
                            cd ${APK_OUTPUT_PATH}/sign_apk
                            for apk in *.apk; do
                                channel=\$(echo \$apk | sed -n 's/.*_sec_\\(.*\\)_sign\\.apk/\\1/p')
                                if [ -n "\$channel" ]; then
                                    mkdir -p "\$channel"
                                    mv "\$apk" "\$channel/yinchao-v${BUILD_NAME}-${ANDROID_BUILD_NUMBER}-\$channel.apk"
                                fi
                            done
                        """
                        echo "✅ APK 已按渠道拆分并重命名完成"

                        sendDingTalkMessage(
                            "Android 打包完成",
                            "### ✅ Android 打包完成\n- 版本: ${BUILD_NAME} (${ANDROID_BUILD_NUMBER})\n- 产物路径: [smb://10.200.35.17](smb://10.200.35.17)"
                        )
                    }
                }
            }
        }
    }

    post {
        failure {
            script {
                sendDingTalkMessage(
                    "打包失败",
                    "❌ 构建失败 ⚠️\n版本: ${BUILD_NAME} (iOS: ${IOS_BUILD_NUMBER}, Android: ${ANDROID_BUILD_NUMBER})"
                )
            }
        }
    }
}

// ---------------- 自定义函数 ----------------
def sendDingTalkMessage(String title, String content) {
    sh """
        curl -s '${DINGTALK_WEBHOOK}' \
        -H 'Content-Type: application/json' \
        -d '{
            "msgtype": "markdown",
            "markdown": {
                "title": "${title}",
                "text": "${content}"
            }
        }'
    """
}

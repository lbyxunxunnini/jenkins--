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

        // ===================== 钉钉告警 =====================
        DINGTALK_WEBHOOK       = "https://oapi.dingtalk.com/robot/send?access_token=057c702cdb1896282659cd07439846fd07ec052cf599883260c08f289f2cd89f"

        // ===================== APK 加固配置 =====================
        PROTECT_APK            = "${env.PROTECT_APK ?: 'true'}"
        SECAPI_JAR_PATH        = "${EXPORT_PATH}/secapi-4.1.5-SNAPSHOT.jar"
        ANDROID_CHANNELS       = "${env.ANDROID_CHANNELS ?: 'debug'}"
    }

    stages {
        stage('初始化仓库') {
            steps {
                dir('facesong_flutter') {
                    echo "🚀 [1/9] 初始化 Git 仓库..."
                    script {
                        def gitDirExists = fileExists('.git')
                        if (!gitDirExists) {
                            deleteDir()
                            sh "git clone ${env.GIT_REPO} . && git checkout ${env.GIT_REF}"
                        } else {
                            sh "git reset --hard && git clean -fd"
                        }

                        checkout([$class: 'GitSCM',
                            branches: [[name: "$GIT_REF"]],
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
                echo "🔢 [2/9] 版本号确认：BUILD_NAME=${BUILD_NAME}, IOS_BUILD_NUMBER=${IOS_BUILD_NUMBER}, ANDROID_BUILD_NUMBER=${ANDROID_BUILD_NUMBER}"
            }
        }

        stage('Flutter 初始化') {
            steps {
                dir('facesong_flutter') {
                    sh """
                        rm -f ios/Podfile.lock
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
                        def iosBuildResult = 1
                        try {
                            iosBuildResult = sh(script: """
                                fvm flutter build ipa \
                                    --flavor production \
                                    --export-options-plist=${EXPORT_OPTIONS_PLIST_PATH} \
                                    --dart-define-from-file=\${DART_DEFINE_FILE} \
                                    --dart-define=WATERMARK=false \
                                    --dart-define=DISTRIBUTE_CHANNEL=AppStore \
                                    --dart-define=DEV_CONFIG=false \
                                    --build-name ${BUILD_NAME} \
                                    --build-number ${IOS_BUILD_NUMBER}
                            """, returnStatus: true)

                            sh 'find build/ios/ipa -name "*.ipa" || exit 1'
                        } catch (err) {
                            iosBuildResult = 1
                        }

                        if (iosBuildResult == 0) {
                            sendDingTalkMessage(
                                "iOS 打包完成\n",
                                "### ✅ iOS 打包完成\n\n- **版本**: ${BUILD_NAME}\n- **BUILD NUMBER**:${IOS_BUILD_NUMBER}\n- **打包产物**: [smb://10.200.35.17](smb://10.200.35.17)"
                            )
                        } else {
                            sendDingTalkMessage(
                                "iOS 打包失败\n",
                                 "### ❌ iOS 打包失败\n\n- **版本**: ${BUILD_NAME}\n- **BUILD NUMBER**:${IOS_BUILD_NUMBER}\n"
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

        stage('构建 & 加固多渠道 Android APK') {
            when { expression { return env.BUILD_ANDROID == "true" } }
            steps {
                dir('facesong_flutter') {
                    script {
                        def channels = (env.ANDROID_CHANNELS ?: "debug").split(',')
                        echo "🔹 构建渠道: ${channels}"

                        // 存储每个渠道的最终状态
                        def channelResults = [:]

                        channels.each { channel ->
                            def channelDir = "${APK_OUTPUT_PATH}/${channel}"
                            def apkFileName = "app-production-${channel}-release.apk"
                            def apkPath = "${channelDir}/${apkFileName}"  // 最终输出路径

                            // 创建渠道输出目录
                            sh "mkdir -p ${channelDir}"

                            // 复制签名文件
                            sh """
                                cp "${EXPORT_PATH}/key.properties" android/app/key.properties
                                cp "${EXPORT_PATH}/release.keystore" android/app/release.keystore
                            """

                            echo "🟢 [${channel}] 开始构建 APK"

                            // Flutter 构建
                            def buildResult = sh(
                                script: """
                                    sed -i '' 's/minSdk = flutter\\.minSdkVersion/minSdk = 24/' android/app/build.gradle
                                    fvm flutter build apk \
                                        --flavor production \
                                        --release \
                                        --dart-define-from-file="\${DART_DEFINE_FILE}" \
                                        --dart-define=WATERMARK=false \
                                        --dart-define=DEV_CONFIG=false \
                                        --dart-define=DISTRIBUTE_CHANNEL=${channel} \
                                        --build-name="${BUILD_NAME}" \
                                        --build-number="${ANDROID_BUILD_NUMBER}"
                                """,
                                returnStatus: true
                            )

                            def copyResult = 1
                            if (buildResult == 0) {
                                echo "✅ [${channel}] APK 构建成功"

                                // 构建成功后复制到最终输出目录
                                copyResult = sh(
                                    script: """
                                        src_apk="build/app/outputs/flutter-apk/app-production-release.apk"
                                        if [ ! -f "\${src_apk}" ]; then
                                            echo "❌ [${channel}] 未找到 APK 文件: \${src_apk}"
                                            exit 1
                                        fi
                                        cp -v "\${src_apk}" "${apkPath}"
                                        echo "✅ [${channel}] 已复制 APK 文件到: ${apkPath}"
                                    """,
                                    returnStatus: true
                                )
                                if (copyResult != 0) {
                                    echo "❌ [${channel}] APK 复制失败"
                                }
                            } else {
                                echo "❌ [${channel}] APK 构建失败"
                            }

                            // APK 加固
                            def protectResult = "未执行"
                            if (env.PROTECT_APK == "true" && buildResult == 0 && copyResult == 0) {
                                echo "🔒 [${channel}] 开始加固 APK"

                                def protectCommand = """
                                    java -jar "${SECAPI_JAR_PATH}" \
                                    -i 10.200.18.111:8000 \
                                    -u zyljsh \
                                    -a 3e41fc10-8c1b-44c2-9c1c-d3a99b1330ca \
                                    -c ba2749da-6086-41cd-b801-ee75727c4bdd \
                                    -f 0 -t 100000 \
                                    -p "${apkPath}" -d "${channelDir}" \
                                    --action ud --ks 1 -r ${channel}
                                """

                                // 执行加固命令并判断结果
                                def protectExitCode = sh(script: protectCommand, returnStatus: true)
                                if (protectExitCode == 0) {
                                    echo "✅ [${channel}] APK 加固成功"
                                    protectResult = "成功"
                                } else {
                                    echo "❌ [${channel}] APK 加固失败"
                                    protectResult = "失败"
                                }
                            }

                            // 保存渠道结果
                            channelResults[channel] = [
                                build: buildResult == 0 ? "成功" : "失败",
                                copy: copyResult == 0 ? "成功" : "失败",
                                protect: protectResult
                            ]
                        }
                        // 构建 Android 多渠道打包通知内容
                        def summary = "### ✅ Android 多渠道打包完成\n\n"

                        channelResults.each { ch, res ->
                            def buildStatus = res.build == "成功" ? "✅ 构建成功" : "❌ 构建失败"
                            def protectStatus = res.protect == "成功" ? "✅ 加固成功" : "❌ 加固失败"
                            summary += "- **渠道**: ${ch}\n  - 构建状态: ${buildStatus}\n  - 加固状态: ${protectStatus}\n"
                        }

                        // 增加 SMB 链接提示（Markdown 超链接）
                        summary += "- **打包产物**: [smb://10.200.35.17](smb://10.200.35.17)"

                        sendDingTalkMessage("Android 多渠道打包完成", summary)


                        // 增加 SMB 链接提示（Markdown 超链接）
                        summary += "\n请在 [smb://10.200.35.17](smb://10.200.35.17) 查看打包产物"

                        sendDingTalkMessage("Android 多渠道打包完成", summary)


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
                    "❌ 构建失败 ⚠️\niOS: ${BUILD_NAME}(${IOS_BUILD_NUMBER})\nAndroid 渠道: ${ANDROID_CHANNELS} 版本: ${BUILD_NAME}(${ANDROID_BUILD_NUMBER})"
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

pipeline {
    agent any

    environment {
        EXPORT_PATH       = "../pack_alpha"
        EXPORT_OPTIONS    = "${EXPORT_PATH}/export_options.plist"
        OUTPUT_DIR        = "/Users/agi00107/Desktop/outpackage"

        FLUTTER_VERSION   = "${env.FLUTTER_VERSION ?: '3.27.4'}"
        ENABLE_IMPELLER   = "${env.ENABLE_IMPELLER != null ? env.ENABLE_IMPELLER : 'true'}"

        BUILD_NAME        = "${env.VERSION ?: '1.0.0'}"
        BUILD_NUMBER      = "${env.BUILDNUM ?: '1'}"
        APP_ENV           = "${env.APP_ENV ?: 'test'}"
        IOS_CHANNEL       = "${env.IOS_CHANNEL ?: 'debug'}"
        AN_CHANNEL        = "${env.AN_CHANNEL ?: 'debug'}"

        PGYER_API_KEY     = "cc4f2c2299fb7ccb2ce7b1c9581e4d01"
        PGYER_PASSWORD    = "initiai"
        PGYER_UPDATE_DESC = "${env.PGYER_UPDATE_DESC ?: '自动构建上传'}"

        GIT_REPO          = 'git@codeup.aliyun.com:6710bdc09d3c82efe37b13cc/facesong/facesong_flutter.git'
        GIT_CREDENTIAL_ID = 'git-ssh-key'
        DINGTALK_WEBHOOK  = "https://oapi.dingtalk.com/robot/send?access_token=ae7a01ed25859f3e9f995717eacbb5bd67dde43cbcb889c27a5868aad347016a"
        // DINGTALK_WEBHOOK  = "https://oapi.dingtalk.com/robot/send?access_token=0b6aaab68a9a4b2c95826ed0cf6be0fd5d1a7c2e84610f3d20a687f0fc7a2c41"
    }

    stages {

        stage('初始化仓库') {
            steps {
                dir('facesong_flutter') {
                    echo "🚀 初始化仓库并拉取代码"

                    sh '''
                        git reset --hard
                        git clean -fd
                    '''

                    checkout([$class: 'GitSCM',
                        branches: [[name: "$GIT_REF"]],
                        extensions: [[$class: 'CleanBeforeCheckout']],
                        userRemoteConfigs: [[
                            url: env.GIT_REPO,
                            credentialsId: env.GIT_CREDENTIAL_ID
                        ]]
                    ])

                    script {
                        // ⭐ 新增：获取远端最新提交（不受构建成功/失败影响）
                        env.GIT_LATEST_COMMIT = sh(
                            script: '''
                                git fetch origin
                                git log origin/${GIT_REF#*/} -1 \
                                  --pretty=format:"%h | %an | %ad | %s" \
                                  --date=short
                            ''',
                            returnStdout: true
                        ).trim()

                        echo "📌 当前仓库最新提交：${env.GIT_LATEST_COMMIT}"
                    }
                }
            }
        }

        stage('设置 APP 参数') {
            steps {
                script {
                    if (env.APP_ENV == "test") {
                        env.APP_NAME = "音潮Alpha-测试"
                        env.DART_DEFINE_FILE = "${EXPORT_PATH}/alpha_test.env"
                    } else if (env.APP_ENV == "prod") {
                        env.APP_NAME = "音潮Alpha-生产回归"
                        env.DART_DEFINE_FILE = "${EXPORT_PATH}/alpha_pro.env"
                    } else {
                        error "未知 APP_ENV: ${env.APP_ENV}"
                    }
                }
            }
        }

        stage('Flutter 初始化') {
            steps {
                dir('facesong_flutter') {
                    sh '''
                        export PUB_HOSTED_URL=https://pub.flutter-io.cn
                        export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn
                        fvm use "$FLUTTER_VERSION"
                        rm -f ios/Podfile.lock pubspec.lock
                        fvm flutter clean
                        fvm flutter pub get
                    '''
                }
            }
        }

        stage('构建 iOS IPA') {
            when { expression { env.BUILD_IOS == "true" } }
            steps {
                dir('facesong_flutter') {
                    sh '''
                        sh build.sh ipa \
                          --channel "$IOS_CHANNEL" \
                          --flavor alpha \
                          --release \
                          --dart-define-from-file="$DART_DEFINE_FILE" \
                          --build-name="$BUILD_NAME" \
                          --build-number="$BUILD_NUMBER"
                    '''
                }
            }
        }

        stage('发送 iOS 钉钉通知') {
            when { expression { env.BUILD_IOS == "true" } }
            steps {
                script {
                    def timeStr = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("Asia/Shanghai"))

                    def markdownText = """
### 🎉 Jenkins 构建完成（iOS）：${APP_NAME}

- **构建版本**：${BUILD_NAME} (${BUILD_NUMBER})
- **构建分支**：${env.GIT_REF}
- **最新提交**：${env.GIT_LATEST_COMMIT}
- **完成时间**：${timeStr}
                    """.stripIndent()

                    def payload = """
                    {
                      "msgtype": "markdown",
                      "markdown": {
                        "title": "iOS 构建完成：${APP_NAME}",
                        "text": "${markdownText.replace('"','\\"').replace("\n","\\n")}"
                      }
                    }
                    """

                    writeFile file: 'ding_ios.json', text: payload
                    sh "curl -s -X POST '${DINGTALK_WEBHOOK}' -H 'Content-Type: application/json' -d @ding_ios.json"
                }
            }
        }
    }

    post {
        failure {
            script {
                def timeStr = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("Asia/Shanghai"))

                def markdownText = """
### ❌ Jenkins 构建失败：${APP_NAME}

- **构建版本**：${BUILD_NAME} (${BUILD_NUMBER})
- **构建分支**：${env.GIT_REF ?: '未知'}
- **最新提交**：${env.GIT_LATEST_COMMIT ?: '获取失败'}
- **失败时间**：${timeStr}

请前往 Jenkins 查看详细日志。
                """.stripIndent()

                def payload = """
                {
                  "msgtype": "markdown",
                  "markdown": {
                    "title": "构建失败：${APP_NAME}",
                    "text": "${markdownText.replace('"','\\"').replace("\n","\\n")}"
                  }
                }
                """

                writeFile file: 'ding_fail.json', text: payload
                sh "curl -s -X POST '${DINGTALK_WEBHOOK}' -H 'Content-Type: application/json' -d @ding_fail.json"
            }
        }
    }
}

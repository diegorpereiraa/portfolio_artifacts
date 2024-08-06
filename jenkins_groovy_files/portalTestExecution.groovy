def call() {
    pipeline {
        agent {
            kubernetes {
                yaml libraryResource('testPod.yaml')
            }
        }

        parameters {
            string(name: 'Tags', defaultValue: '', description: 'Insert the correspondent test tag to be executed. If you want to run the full regression please leave the field blank')
            string(name: 'TEST_DATA_TAGS', defaultValue: '', description: 'Insert the correspondent test data tags to be executed.')
            string(name: 'TOOL_JAR_VERSION', defaultValue: '1.26.1', description: "Please insert the JAR version." +
                    " If you want to execute the Injection on the REST Services this field is mandatory")
        }

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(daysToKeepStr: '15', artifactDaysToKeepStr: '15'))
        }

        environment {
            GROUP_ID = 'repository.path'
            NAME = 'fileName'
            REPOSITORY = 'test-releases'
            JAR_TEST_NAME = 'fileName.jar'

            REPO_ADDRESS = 'repo-sonatype-repo.cicd:8081'
            REPO_PASSWORD = credentials('repo-password')
            REPO_USERNAME = credentials('repo-username')

            COMPONENTS_DIR = 'components'
            ACCEPTANCE = 'acceptance'
            MAX_THREADS = '1'
            HUB_URL = 'http://selenium-selenium-hub.cicd:4444/wd/hub'

            TAGS = '@ui and @dev and not @wip and not @unstable'
            DATA_CREATION_TAGS = "@test_data_generation and ${params.TEST_DATA_TAGS}"
            MS_TEAMS_WEB_HOOK = 'https://test.webhook.office.com/webhookb2/1234567890'

            TOOL_REPORTS_COMPRESSED_FOLDER_NAME = 'reports.tar.gz'
            TOOL_TEST_DATA_COMPRESSED_FOLDER_NAME = 'atf_test_data.tar.gz'
            S3_BUCKET_URL_FOR_REPORTS = "s3://qa-test-report/webpage/"
        }
        }

        stages {

            stage('Lint') {
                steps {
                    container('yamllint') {
                        sh 'yamllint -d "{rules: {line-length: {max: 800}}}" .'
                    }
                }
            }

            stage('Setup') {
                steps {
                    script {
                        if (!params.Tags.equals('') && params.Tags != null) {
                            TAGS = "${TAGS} and ${params.Tags}"
                        }
                        container('openjdk') {
                            withCredentials([file(credentialsId: 'tool_lic', variable: 'TOOL_LIC')]) {
                                sh "cp $TOOL_LIC ."
                            }

                            sh "wget -O ${JAR_TEST_NAME} 'http://${REPO_USERNAME}:${REPO_PASSWORD}@${REPO_ADDRESS}/service/rest/v1/search/download?sort=version&repository=${REPOSITORY}&group=${GROUP_ID}&name=${NAME}'"
                            sh "mkdir -p data/"
                            sh "ls -la"
                            if (DATA_CREATION_TAGS =~ "@alert_creation") {
                                build job: 'qa-test-data-generation', propagate: false, parameters: [
                                        string(name: 'ALERTS_CREATION_TAGS', value: "${env.DATA_CREATION_TAGS}"),
                                        string(name: 'TOOL_JAR_VERSION', value: "${params.TOOL_JAR_VERSION}")
                                ]
                            }
                            copyArtifacts filter: "${TOOL_TEST_DATA_COMPRESSED_FOLDER_NAME}", projectName: 'qa-test-data-generation', selector: lastCompleted(), optional: false
                            sh "cd data"
                            sh "tar -zxvf ./${TOOL_TEST_DATA_COMPRESSED_FOLDER_NAME}"
                            sh "cd .."
                            sh "ls -la data/"
                        }
                    }
                }
            }

            stage('Test Execution') {
                steps {
                    script {
                        def stdout = true
                        container('openjdk') {
                            try {
                                sh "java -jar ${JAR_TEST_NAME} run --components-dir ${COMPONENTS_DIR} --acceptance ${ACCEPTANCE} --threads ${MAX_THREADS} --tags \'${TAGS}\' --device chrome --hub-url ${HUB_URL} --config-file-ui config/chrome.cfg.yaml --salt ngm --report 'Portal Execution'"
                                //sh "java -jar ${JAR_TEST_NAME} run --components-dir ${COMPONENTS_DIR} --acceptance ${ACCEPTANCE} --threads ${MAX_THREADS} --tags \'${TAGS}\' --device firefox --hub-url ${HUB_URL} --config-file-ui config/firefox.cfg.yaml --salt ngm"
                            }
                            catch (exc) {
                                stdout = false
                            }
                            sh "ls -la "
                            sh "tar -czvf ${TOOL_REPORTS_COMPRESSED_FOLDER_NAME} ./reports"
                        }
                        /*container('aws-helm3-kubectl') {
                            def currentTime = sh(returnStdout: true, script: 'cat data/currentTime.txt').trim()
                            sh "echo ${currentTime}"
                            sh "aws s3 cp ${TOOL_REPORTS_COMPRESSED_FOLDER_NAME} ${S3_BUCKET_URL_FOR_REPORTS}/${currentTime}"
                        }*/
                        if(stdout==false) {
                            currentBuild.result = 'FAILURE'
                        }
                    }
                }
            }
        }

        post {
            always {
                cucumber jsonReportDirectory: '.', fileIncludePattern: "*.json", showPlatform: true
                archiveArtifacts artifacts: "${TOOL_REPORTS_COMPRESSED_FOLDER_NAME}", allowEmptyArchive: true
                archiveArtifacts artifacts: "reports/*/tool-report/**/**/*.*, *.json", allowEmptyArchive: true
                //notifyMSTeams()
            }
            cleanup {
                cleanWs()
            }
        }
    }
}

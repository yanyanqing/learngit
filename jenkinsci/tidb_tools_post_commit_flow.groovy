#!groovy

node('material') {
    def workspace = pwd()
    env.GOPATH = "${workspace}/go:/go"
    env.GOROOT = "/usr/local/go"
    env.PATH = "${workspace}/go/bin:/go/bin:${env.GOROOT}/bin:/bin:${env.PATH}"
    def pingcap = "${workspace}/go/src/github.com/pingcap"
    def tools_path = "${pingcap}/tidb-tools"
    def platform = "linux-amd64"
    def binary = "/binary_registry"
    def githash_tools
    def getChangeLogText, getBuildDuration

    catchError {
        stage('SCM Checkout') {
            // tidb-tools
            dir("${tools_path}") {
                retry(3) {
                    git credentialsId: 'github-liuyin', url: 'git@github.com:pingcap/tidb-tools.git'
                }
                githash_tools = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }

            // common
            fileLoader.withGit('git@github.com:pingcap/SRE.git', 'master', 'github-liuyin', '') {
                getChangeLogText = fileLoader.load('jenkinsci/common/get_changelog_text.groovy')
                getBuildDuration = fileLoader.load('jenkinsci/common/get_build_duration.groovy')
            }
        }

        stage('Build') {
            sh """
            cd ${tools_path}
            make importer
            make syncer
            make checker
            make loader
            """
        }

        stage('Stash') {
            sh """
            rm -rf release

            # tidb-tools
            mkdir -p release/tools/bin/${platform} release/tools/conf release/tools/src
            cp ${tools_path}/bin/importer release/tools/bin/${platform}/
            cp ${tools_path}/bin/syncer release/tools/bin/${platform}/
            cp ${tools_path}/bin/checker release/tools/bin/${platform}/
            cp ${tools_path}/bin/loader release/tools/bin/${platform}/
            cp ${tools_path}/syncer/config.toml release/tools/conf/syncer.toml
            echo '${githash_tools}' > release/tools/src/.githash
            """

            stash includes: 'go/src/github.com/pingcap/**', name: 'source-pingcap'
        }

        stage('Test') {
            node('worker') {
                deleteDir()
                unstash 'source-pingcap'
                sh """
                cd ${tools_path} && make test
                """
            }
        }

        stage('Save Binary') {
            def target = "${binary}/tools/${githash_tools}"
            sh """
            rm -rf ${target} && mkdir -p ${target}
            cp -R release/tools/* ${target}/
            ln -sfT ${target} ${binary}/tools_latest
            """
        }

        stage('Publish Binary') {
            node('master') {
                def branches = [:]

                branches['linux-amd64'] = {
                    def target_platform = 'linux-amd64'
                    def target_package = "tidb-tools-latest-${target_platform}"

                    sh """
                    rm -rf ${target_package} && mkdir -p ${target_package}/bin && mkdir -p ${target_package}/conf
                    # TiDB-Tools
                    cp ${binary}/tools/${githash_tools}/bin/${target_platform}/* ${target_package}/bin/
                    cp ${binary}/tools/${githash_tools}/conf/syncer.toml ${target_package}/conf/syncer.toml
                    # Package
                    tar czvf ${target_package}.tar.gz ${target_package}
                    sha256sum ${target_package}.tar.gz > ${target_package}.sha256
                    md5sum ${target_package}.tar.gz > ${target_package}.md5
                    # Upload
                    /usr/bin/upload.py ${target_package}.tar.gz ${target_package}.tar.gz
                    /usr/bin/upload.py ${target_package}.sha256 ${target_package}.sha256
                    /usr/bin/upload.py ${target_package}.md5 ${target_package}.md5
                    """
                }

                parallel branches
            }
        }

        currentBuild.result = "SUCCESS"
    }

    def changeLogText = getChangeLogText()

    def duration = getBuildDuration()

    def slackMsg = "" +
            "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}, " +
            "${changeLogText}" + "\n" +
            "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#tidb-tools', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
    } else {
//        slackSend channel: '#tidb-tools', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
    }
}

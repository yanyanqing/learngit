#!groovy

node('material') {
    def workspace = pwd()
    env.GOPATH = "${workspace}/go:/go"
    env.GOROOT = "/usr/local/go"
    env.PATH = "${workspace}/go/bin:/go/bin:${env.GOROOT}/bin:/bin:${env.PATH}"
    def pingcap = "${workspace}/go/src/github.com/pingcap"
    def pd_path = "${pingcap}/pd"
    def tidb_path = "${pingcap}/tidb"
    def tidb_test_path = "${pingcap}/tidb-test"
    def platform = "linux-amd64"
    def platform_centos6 = "linux-amd64-centos6"
    def binary = "/binary_registry"
    def githash_pd
    def genIntegrationTest, getChangeLogText, getBuildDuration

    catchError {
        stage('SCM Checkout') {
            // pd
            dir("${pd_path}") {
                retry(3) {
                    git credentialsId: 'github-liuyin', url: 'git@github.com:pingcap/pd.git'
                }
                githash_pd = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }

            // tidb
            dir("${tidb_path}") {
                retry(3) {
                    git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb.git'
                }
            }

            // tidb_test
            dir("${tidb_test_path}") {
                retry(3) {
                    git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb-test.git'
                }
            }

            // common
            fileLoader.withGit('git@github.com:pingcap/SRE.git', 'master', 'github-liuyin', '') {
                genIntegrationTest = fileLoader.load('jenkinsci/common/gen_integration_test.groovy')
                getChangeLogText = fileLoader.load('jenkinsci/common/get_changelog_text.groovy')
                getBuildDuration = fileLoader.load('jenkinsci/common/get_build_duration.groovy')
            }
        }

        stage('Build') {
            def branches = [:]
            branches["linux-amd64"] = {
                sh """
                rm -rf ${pingcap}/vendor
                cd ${pd_path} && make
                cd ${tidb_path} && make
                ln -s ${tidb_path}/_vendor/src ${pingcap}/vendor
                """
            }
            branches["linux-amd64-centos6"] = {
                node('material-centos6') {
                    // pd
                    dir("${pd_path}") {
                        retry(3) {
                            git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/pd.git'
                        }
                    }

                    sh """
                    cd ${pd_path}
                    git checkout ${githash_pd}
                    make
                    rm -rf ${workspace}/release && mkdir -p ${workspace}/release/pd/bin/${platform_centos6}
                    cp bin/* ${workspace}/release/pd/bin/${platform_centos6}/
                    git checkout master
                    """

                    stash includes: "release/pd/bin/${platform_centos6}/**", name: "release_pd_${platform_centos6}"
                }
            }

            parallel branches
        }

        stage('Stash') {
            sh """
            rm -rf release

            # pd
            mkdir -p release/pd/bin/${platform} release/pd/conf release/pd/src
            cp ${pd_path}/bin/* release/pd/bin/${platform}/
            cp ${pd_path}/conf/config.toml release/pd/conf/
            echo '${githash_pd}' > release/pd/src/.githash

            # tikv
            mkdir -p release/tikv
            cp -R ${binary}/tikv_latest/* release/tikv/
            """

            unstash "release_pd_${platform_centos6}"

            stash includes: 'go/src/github.com/pingcap/**', name: 'source-pingcap'
            stash includes: "release/pd/bin/${platform}/**", name: "release-pd-${platform}"
            stash includes: "release/tikv/bin/${platform}/**", name: "release-tikv-${platform}"
        }

        stage('Test') {
            def branches = [:]

            branches["PD Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    rm -rf ${pingcap}/vendor
                    cd ${pd_path} && make dev
                    """
                }
            }

            genIntegrationTest(branches, platform, tidb_path, tidb_test_path)

            parallel branches
        }

        stage('Save Binary') {
            def target = "${binary}/pd/${githash_pd}"
            sh """
            rm -rf ${target} && mkdir -p ${target}
            cp -R release/pd/* ${target}/
            ln -sfT ${target} ${binary}/pd_latest
            """
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
        slackSend channel: '#pd', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
    } else {
//        slackSend channel: '#pd', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
        build job: 'TIDB_LATEST_PUBLISH', wait: false
    }
}

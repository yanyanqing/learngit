#!groovy

node('material') {
    def workspace = pwd()
    env.GOPATH = "${workspace}/go:/go"
    env.GOROOT = "/usr/local/go"
    env.PATH = "${workspace}/go/bin:/go/bin:${env.GOROOT}/bin:/bin:${env.PATH}"
    def pingcap = "${workspace}/go/src/github.com/pingcap"
    def tidb_path = "${pingcap}/tidb"
    def tidb_test_path = "${pingcap}/tidb-test"
    def platform = "linux-amd64"
    def platform_centos6 = "linux-amd64-centos6"
    def binary = "/binary_registry"
    def githash_tidb
    def genTiDBTest, genIntegrationTest

    catchError {
        stage('SCM Checkout') {
            // tidb
            dir("${tidb_path}") {
                retry(3) {
                    git credentialsId: 'github-liuyin', url: 'git@github.com:pingcap/tidb.git'
                }
                githash_tidb = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }

            // tidb_test
            dir("${tidb_test_path}") {
                retry(3) {
                    git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb-test.git'
                }
            }

            // mybatis
            dir("mybatis3") {
                retry(3) {
                    git changelog: false, credentialsId: 'github-liuyin', poll: false, branch: 'travis-tidb', url: 'git@github.com:qiuyesuifeng/mybatis-3.git'
                }
            }

            // common
            fileLoader.withGit('git@github.com:pingcap/SRE.git', 'master', 'github-liuyin', '') {
                genTiDBTest = fileLoader.load('jenkinsci/common/gen_tidb_test.groovy')
                genIntegrationTest = fileLoader.load('jenkinsci/common/gen_integration_test.groovy')
            }
        }

        stage('Build') {
            def branches = [:]
            branches["linux-amd64"] = {
                // tidb
                sh """
                rm -rf ${pingcap}/vendor
                cd ${tidb_path} && make
                ln -s ${tidb_path}/_vendor/src ${pingcap}/vendor
                """
            }
            branches["linux-amd64-centos6"] = {
                node('material-centos6') {
                    // tidb
                    dir("${tidb_path}") {
                        retry(3) {
                            git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb.git'
                        }
                    }

                    sh """
                    cd ${tidb_path}
                    git checkout ${githash_tidb}
                    make
                    rm -rf ${workspace}/release && mkdir -p ${workspace}/release/tidb/bin/${platform_centos6}
                    cp bin/tidb-server ${workspace}/release/tidb/bin/${platform_centos6}/
                    git checkout master
                    """

                    stash includes: "release/tidb/bin/${platform_centos6}/**", name: "release_tidb_${platform_centos6}"
                }
            }
            parallel branches
        }

        stage('Stash') {
            sh """
            rm -rf release

            # tidb
            mkdir -p release/tidb/bin/${platform} release/tidb/conf release/tidb/src
            cp ${tidb_path}/bin/tidb-server release/tidb/bin/${platform}/
            echo '${githash_tidb}' > release/tidb/src/.githash

            # pd
            mkdir -p release/pd
            cp -R ${binary}/pd_latest/* release/pd/

            # tikv
            mkdir -p release/tikv
            cp -R ${binary}/tikv_latest/* release/tikv/
            """

            unstash "release_tidb_${platform_centos6}"

            stash includes: 'go/src/github.com/pingcap/**', name: 'source-pingcap'
            stash includes: 'mybatis3/**', name: 'source-mybatis3'
            stash includes: "release/pd/bin/${platform}/**", name: "release-pd-${platform}"
            stash includes: "release/tikv/bin/${platform}/**", name: "release-tikv-${platform}"
        }

        stage('Test') {
            def branches = [:]
            genTiDBTest(branches)
            genIntegrationTest(branches)
            parallel branches
        }

        stage('Save Binary') {
            def target = "${binary}/tidb/${githash_tidb}"
            sh """
            rm -rf ${target} && mkdir -p ${target}
            cp -R release/tidb/* ${target}/
            ln -sfT ${target} ${binary}/tidb_latest
            """
        }

        currentBuild.result = "SUCCESS"
    }

    def changeLogText = ""
    for (int i = 0; i < currentBuild.changeSets.size(); i++) {
        for (int j = 0; j < currentBuild.changeSets[i].items.length; j++) {
            def commitId = "${currentBuild.changeSets[i].items[j].commitId}"
            def commitMsg = "${currentBuild.changeSets[i].items[j].msg}"
            changeLogText += "\n" + commitId.substring(0, 7) + " " + commitMsg
        }
    }

    def duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000

    def slackMsg = "" +
            "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}, " +
            "${changeLogText}" + "\n" +
            "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#tidb', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
    } else {
//        slackSend channel: '#tidb', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
        build job: 'TIDB_LATEST_PUBLISH', wait: false
    }
}

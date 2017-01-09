#!groovy

node('material-branch') {
    def workspace = pwd()
    env.GOPATH = "${workspace}/go:/go"
    env.GOROOT = "/usr/local/go"
    env.PATH = "${workspace}/go/bin:/go/bin:${env.GOROOT}/bin:/home/jenkins/.cargo/bin:/bin:${env.PATH}"
    env.http_proxy = "${proxy}"
    env.https_proxy = "${proxy}"
    env.CARGO_TARGET_DIR = "/home/jenkins/.target"
    env.LIBRARY_PATH = "/usr/local/lib:${env.LIBRARY_PATH}"
    env.LD_LIBRARY_PATH = "/usr/local/lib:${env.LD_LIBRARY_PATH}"
    def pingcap = "${workspace}/go/src/github.com/pingcap"
    def tidb_path = "${pingcap}/tidb"
    def tidb_test_path = "${pingcap}/tidb-test"
    def tikv_path = "${pingcap}/tikv"
    def pd_path = "${pingcap}/pd"
    def platform = "linux-amd64"
    def platform_centos6 = "linux-amd64-centos6"
    def binary = "/binary_registry"
    def githash_tidb, githash_tikv, githash_pd
    def genTiDBTest, genIntegrationTest, getChangeLogText, getBuildDuration

    catchError {
        stage('SCM Checkout') {
            // tidb
            dir("${tidb_path}") {
                retry(3) {
                    git credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb.git', branch: "${tidb_branch}"
                }
                githash_tidb = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }

            // tikv
            dir("${tikv_path}") {
                retry(3) {
                    git credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tikv.git', branch: "${tikv_branch}"
                }
                githash_tikv = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }

            // pd
            dir("${pd_path}") {
                retry(3) {
                    git credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/pd.git', branch: "${pd_branch}"
                }
                githash_pd = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
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
                getChangeLogText = fileLoader.load('jenkinsci/common/get_changelog_text.groovy')
                getBuildDuration = fileLoader.load('jenkinsci/common/get_build_duration.groovy')
            }
        }

        stage('Build') {
            def branches = [:]
            branches["linux-amd64"] = {
                // tidb & pd
                sh """
                rm -rf ${pingcap}/vendor
                cd ${pd_path} && make
                cd ${tidb_path} && make
                ln -s ${tidb_path}/_vendor/src ${pingcap}/vendor
                """

                // tikv
                sh """
                rustup default nightly-2016-08-06
                cd ${tikv_path}
                make static_release
                """
            }
            branches["linux-amd64-centos6"] = {
                node('material-branch-centos6') {
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
                    cp bin/pd-server ${workspace}/release/pd/bin/${platform_centos6}/
                    git checkout master
                    """

                    stash includes: "release/pd/bin/${platform_centos6}/**", name: "release_pd_${platform_centos6}"

                    // tikv
                    dir("$tikv_path") {
                        retry(3) {
                            git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tikv.git'
                        }
                    }

                    sh """
                    rustup default nightly-2016-08-06
                    cd ${tikv_path}
                    git checkout ${githash_tikv}
                    scl enable devtoolset-4 python27 "make static_release"
                    rm -rf ${workspace}/release && mkdir -p ${workspace}/release/tikv/bin/${platform_centos6}
                    mv bin/* ${workspace}/release/tikv/bin/${platform_centos6}/
                    git checkout master
                    """

                    stash includes: "release/tikv/bin/${platform_centos6}/**", name: "release_tikv_${platform_centos6}"
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
            mkdir -p release/pd/bin/${platform} release/pd/conf release/pd/src
            cp ${pd_path}/bin/pd-server release/pd/bin/${platform}/
            cp ${pd_path}/conf/config.toml release/pd/conf/
            echo '${githash_pd}' > release/pd/src/.githash

            # tikv
            mkdir -p release/tikv/bin/${platform} release/tikv/conf release/tikv/src
            mv ${tikv_path}/bin/* release/tikv/bin/${platform}/
            cp ${tikv_path}/etc/config-template.toml release/tikv/conf/
            echo '${githash_tikv}' > release/tikv/src/.githash
            """

            unstash "release_tidb_${platform_centos6}"
            unstash "release_pd_${platform_centos6}"
            unstash "release_tikv_${platform_centos6}"

            stash includes: 'go/src/github.com/pingcap/**', name: 'source-pingcap'
            stash includes: 'mybatis3/**', name: 'source-mybatis3'
            stash includes: "release/pd/bin/${platform}/**", name: "release-pd-${platform}"
            stash includes: "release/tikv/bin/${platform}/**", name: "release-tikv-${platform}"
        }

        stage('Test') {
            def branches = [:]

            genTiDBTest(branches, pingcap, tidb_path, tidb_test_path)

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

            branches["TiKV Test"] = {
                sh """
                rustup default nightly-2016-12-19
                cd ${tikv_path}
                make test
                """
            }

            genIntegrationTest(branches, platform, tidb_path, tidb_test_path)

            parallel branches
        }

        stage('Save Binary') {
            // tidb
            def tidb_target = "${binary}/tidb/${githash_tidb}"
            sh """
            rm -rf ${tidb_target} && mkdir -p ${tidb_target}
            cp -R release/tidb/* ${tidb_target}/
            """

            // pd
            def pd_target = "${binary}/pd/${githash_pd}"
            sh """
            rm -rf ${pd_target} && mkdir -p ${pd_target}
            cp -R release/pd/* ${pd_target}/
            """

            // tikv
            def tikv_target = "${binary}/tikv/${githash_tikv}"
            sh """
            rm -rf ${tikv_target} && mkdir -p ${tikv_target}
            cp -R release/tikv/* ${tikv_target}/
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
        slackSend channel: '#dt', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
    } else {
//        slackSend channel: '#dt', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"

        if (publish_tag != "NOT_PUBLISH") {
            build job: 'TIDB_SPEC_PUBLISH', parameters: [
                            string(name: 'githash_tidb', value: "${githash_tidb}"),
                            string(name: 'githash_tikv', value: "${githash_tikv}"),
                            string(name: 'githash_pd', value: "${githash_pd}"),
                            string(name: 'version', value: "${publish_tag}")
                    ], wait: false
        }
    }
}

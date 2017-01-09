#!groovy

node('material') {
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
    def tikv_path = "${pingcap}/tikv"
    def tidb_path = "${pingcap}/tidb"
    def tidb_test_path = "${pingcap}/tidb-test"
    def platform = "linux-amd64"
    def platform_centos6 = "linux-amd64-centos6"
    def binary = "/binary_registry"
    def githash_tikv
    def genIntegrationTest, getChangeLogText, getBuildDuration

    catchError {
        stage('SCM Checkout') {
            // tikv
            dir("${tikv_path}") {
                retry(3) {
                    git credentialsId: 'github-liuyin', url: 'git@github.com:pingcap/tikv.git'
                }
                githash_tikv = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
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
                // tikv
                sh """
                rustup default nightly-2016-12-19
                cd ${tikv_path}
                make static_release
                """

                // tidb
                sh """
                rm -rf ${pingcap}/vendor
                cd ${tidb_path} && make
                ln -s ${tidb_path}/_vendor/src ${pingcap}/vendor
                """
            }
            branches["linux-amd64-centos6"] = {
                node('material-centos6') {
                    // tikv
                    dir("$tikv_path") {
                        retry(3) {
                            git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tikv.git'
                        }
                    }

                    sh """
                    rustup default nightly-2016-12-19
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

            # tikv
            mkdir -p release/tikv/bin/${platform} release/tikv/conf release/tikv/src
            mv ${tikv_path}/bin/* release/tikv/bin/${platform}/
            cp ${tikv_path}/etc/config-template.toml release/tikv/conf/
            echo '${githash_tikv}' > release/tikv/src/.githash

            # pd
            mkdir -p release/pd
            cp -R ${binary}/pd_latest/* release/pd/
            """

            unstash "release_tikv_${platform_centos6}"

            stash includes: 'go/src/github.com/pingcap/**', name: 'source-pingcap'
            stash includes: "release/pd/bin/${platform}/**", name: "release-pd-${platform}"
            stash includes: "release/tikv/bin/${platform}/**", name: "release-tikv-${platform}"
        }

        stage('Test') {
            def branches = [:]

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
            def target = "${binary}/tikv/${githash_tikv}"
            sh """
            rm -rf ${target} && mkdir -p ${target}
            cp -R release/tikv/* ${target}/
            ln -sfT ${target} ${binary}/tikv_latest
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
        slackSend channel: '#kv', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
    } else {
//        slackSend channel: '#kv', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
        build job: 'TIDB_LATEST_PUBLISH', wait: false
    }
}

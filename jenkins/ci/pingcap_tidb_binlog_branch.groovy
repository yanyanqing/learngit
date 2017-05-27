def call(TIDB_BRANCH, TIKV_BRANCH, PD_BRANCH) {

    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.GOROOT = "/usr/local/go"
    env.GOPATH = "/go"
    env.PATH = "${env.GOROOT}/bin:/home/jenkins/bin:/bin:${env.PATH}"

    catchError {
        stage('Prepare') {
            node('centos7_build') {
                def ws = pwd()

                // tidb-binlog
                dir("go/src/github.com/pingcap/tidb-binlog") {
                    // checkout
                    checkout scm

                    // build
                    sh "GOPATH=${ws}/go:$GOPATH make"

                    dir("test") {
                        sh "GOPATH=${ws}/go/src/github.com/pingcap/tidb-binlog/_vendor:${ws}/go:$GOPATH go build"
                    }
                }
                stash includes: "go/src/github.com/**", name: "tidb-binlog"
            }

            // tidb
            def tidb_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb/${TIDB_BRANCH}/centos7/sha1").trim()
            sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"

            // tikv
            def tikv_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tikv/${TIKV_BRANCH}/unportable_centos7/sha1").trim()
            sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tikv/${tikv_sha1}/unportable_centos7/tikv-server.tar.gz | tar xz"

            // pd
            def pd_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/pd/${PD_BRANCH}/centos7/sha1").trim()
            sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"

            stash includes: "bin/**", name: "binaries"
        }

        stage('Test') {
            def tests = [:]

            tests["Unit Test"] = {
                node("test") {
                    def ws = pwd()
                    deleteDir()
                    unstash 'tidb-binlog'

                    dir("go/src/github.com/pingcap/tidb-binlog") {
                        sh "GOPATH=${ws}/go:$GOPATH make test"
                    }
                }
            }

            tests["Integration Test"] = {
                node("test") {
                    def ws = pwd()
                    deleteDir()
                    unstash 'tidb-binlog'
                    unstash 'binaries'

                    try {
                        sh """
                        killall -9 drainer || true
                        killall -9 tidb-server || true
                        killall -9 pump || true
                        killall -9 tikv-server || true
                        killall -9 pd-server || true
                        bin/pd-server --name=pd --data-dir=pd &>pd_binlog_test.log &
                        sleep 20
                        bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_binlog_test.log &
                        sleep 40
                        go/src/github.com/pingcap/tidb-binlog/bin/pump --addr=127.0.0.1:8250 --socket=./pump.sock &>pump_binlog_test.log &
                        sleep 10
                        bin/tidb-server --store=tikv --path=127.0.0.1:2379 --binlog-socket=./pump.sock &>source_tidb_binlog_test.log &
                        sleep 10
                        bin/tidb-server -P 3306 --status=20080 &>target_tidb_binlog_test.log &
                        sleep 10
                        go/src/github.com/pingcap/tidb-binlog/bin/drainer --config=go/src/github.com/pingcap/tidb-binlog/cmd/drainer/drainer.toml &>drainer_binlog_test.log &
                        sleep 10
                        """

                        dir("go/src/github.com/pingcap/tidb-binlog/test") {
                            sh "GOPATH=${ws}/go:$GOPATH ./test --config=config.toml"
                        }
                    } catch (err) {
                        sh "cat pd_binlog_test.log"
                        sh "cat tikv_binlog_test.log"
                        sh "cat pump_binlog_test.log"
                        sh "cat source_tidb_binlog_test.log"
                        sh "cat target_tidb_binlog_test.log"
                        sh "cat drainer_binlog_test.log"
                        throw err
                    } finally {
                        sh """
                        killall -9 drainer || true
                        killall -9 tidb-server || true
                        killall -9 pump || true
                        killall -9 tikv-server || true
                        killall -9 pd-server || true
                        """
                    }
                }
            }

            parallel tests
        }

        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def getChangeLogText = {
            def changeLogText = ""
            for (int i = 0; i < currentBuild.changeSets.size(); i++) {
                for (int j = 0; j < currentBuild.changeSets[i].items.length; j++) {
                    def commitId = "${currentBuild.changeSets[i].items[j].commitId}"
                    def commitMsg = "${currentBuild.changeSets[i].items[j].msg}"
                    changeLogText += "\n" + "`${commitId.take(7)}` ${commitMsg}"
                }
            }
            return changeLogText
        }
        def changelog = getChangeLogText()
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration}` Mins" +
        "${changelog}" + "\n" +
        "${env.RUN_DISPLAY_URL}"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#tidb-tools', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this

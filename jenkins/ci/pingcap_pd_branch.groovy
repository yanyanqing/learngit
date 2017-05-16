def call(TIDB_TEST_BRANCH, TIDB_BRANCH, TIKV_BRANCH) {

    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.GOROOT = "/usr/local/go"
    env.GOPATH = "/go"
    env.PATH = "${env.GOROOT}/bin:/home/jenkins/bin:/bin:${env.PATH}"

    catchError {
        stage('Prepare') {
            // pd
            node('centos7_build') {
                def ws = pwd()
                dir("go/src/github.com/pingcap/pd") {
                    // checkout
                    checkout scm
                    // build
                    sh "GOPATH=${ws}/go:$GOPATH make"
                }
                stash includes: "go/src/github.com/pingcap/pd/**", name: "pd"
            }

            // tidb
            node('centos7_build') {
                def ws = pwd()
                dir("go/src/github.com/pingcap/tidb") {
                    // checkout
                    git changelog: false, credentialsId: 'github-iamxy-ssh', poll: false, url: 'git@github.com:pingcap/tidb.git', branch: "${TIDB_BRANCH}"
                    sh "GOPATH=${ws}/go:$GOPATH make parser"
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb/${TIDB_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"
                }
                stash includes: "go/src/github.com/pingcap/tidb/**", name: "tidb"
            }

            // tidb-test
            dir("go/src/github.com/pingcap/tidb-test") {
                // checkout
                git changelog: false, credentialsId: 'github-iamxy-ssh', poll: false, url: 'git@github.com:pingcap/tidb-test.git', branch: "${TIDB_TEST_BRANCH}"
            }
            stash includes: "go/src/github.com/pingcap/tidb-test/**", name: "tidb-test"

            // tikv
            def tikv_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tikv/${TIKV_BRANCH}/centos7/sha1").trim()
            sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"

            unstash 'pd'
            sh "cp go/src/github.com/pingcap/pd/bin/pd-server bin/pd-server && rm -rf go/src/github.com/pingcap/pd"

            stash includes: "bin/**", name: "binaries"
        }

        stage('Test') {
            def tests = [:]

            tests["PD Test"] = {
                node("test") {
                    def ws = pwd()
                    deleteDir()
                    unstash 'pd'

                    dir("go/src/github.com/pingcap/pd") {
                        sh "GOPATH=${ws}/go:$GOPATH make test"
                    }
                }
            }

            def run_integration_ddl_test = { ddltest ->
                def ws = pwd()
                deleteDir()
                unstash 'tidb'
                unstash 'tidb-test'
                unstash 'binaries'

                try {
                    sh """
                    killall -9 ddltest_tidb-server || true
                    killall -9 tikv-server || true
                    killall -9 pd-server || true
                    bin/pd-server --name=pd --data-dir=pd &>pd_ddl_test.log &
                    sleep 30
                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_ddl_test.log &
                    sleep 10
                    """

                    timeout(10) {
                        dir("go/src/github.com/pingcap/tidb-test") {
                            sh """
                            ln -s tidb/_vendor/src ../vendor
                            cp ${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server ddl_test/ddltest_tidb-server
                            cd ddl_test && GOPATH=${ws}/go:$GOPATH ./run-tests.sh -check.f='${ddltest}'
                            """
                        }
                    }
                } catch (err) {
                    sh "cat pd_ddl_test.log"
                    sh "cat tikv_ddl_test.log"
                    throw err
                } finally {
                    sh "killall -9 ddltest_tidb-server || true"
                    sh "killall -9 tikv-server || true"
                    sh "killall -9 pd-server || true"
                }
            }

            tests["Integration DDL Insert Test"] = {
                node("test") {
                    run_integration_ddl_test('TestDDLSuite.TestSimple.*Insert')
                }
            }

            tests["Integration DDL Update Test"] = {
                node("test") {
                    run_integration_ddl_test('TestDDLSuite.TestSimple.*Update')
                }
            }

            tests["Integration DDL Delete Test"] = {
                node("test") {
                    run_integration_ddl_test('TestDDLSuite.TestSimple.*Delete')
                }
            }

            tests["Integration DDL Other Test"] = {
                node("test") {
                    run_integration_ddl_test('TestDDLSuite.TestSimp(le\$|leMixed|leInc)')
                }
            }

            tests["Integration DDL Column and Index Test"] = {
                node("test") {
                    run_integration_ddl_test('TestDDLSuite.Test(Column|Index)')
                }
            }

            tests["Integration Connection Test"] = {
                node("test") {
                    def ws = pwd()
                    deleteDir()
                    unstash 'tidb'
                    unstash 'tidb-test'
                    unstash 'binaries'

                    try {
                        sh """
                        killall -9 tikv-server || true
                        killall -9 pd-server || true
                        bin/pd-server --name=pd --data-dir=pd &>pd_conntest.log &
                        sleep 30
                        bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_conntest.log &
                        sleep 10
                        """

                        dir("go/src/github.com/pingcap/tidb") {
                            sh """
                            GOPATH=`pwd`/_vendor:${ws}/go:$GOPATH CGO_ENABLED=1 go test --args with-tikv store/tikv/*.go
                            """
                        }
                    } catch (err) {
                        sh "cat pd_conntest.log"
                        sh "cat tikv_conntest.log"
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }

            def run_integration_other_test = { mytest ->
                def ws = pwd()
                deleteDir()
                unstash 'tidb'
                unstash 'tidb-test'
                unstash 'binaries'

                try {
                    sh """
                    killall -9 tikv-server || true
                    killall -9 pd-server || true
                    bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                    sleep 30
                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                    sleep 10
                    """

                    dir("go/src/github.com/pingcap/tidb-test") {
                        sh """
                        ln -s tidb/_vendor/src ../vendor
                        GOPATH=${ws}/go:$GOPATH TIKV_PATH='127.0.0.1:2379' TIDB_TEST_STORE_NAME=tikv make ${mytest}
                        """
                    }
                } catch (err) {
                    sh "cat pd_${mytest}.log"
                    sh "cat tikv_${mytest}.log"
                    throw err
                } finally {
                    sh "killall -9 tikv-server || true"
                    sh "killall -9 pd-server || true"
                }
            }

            tests["Integration TiDB Test"] = {
                node('test') {
                    run_integration_other_test('tidbtest')
                }
            }

            tests["Integration MySQL Test"] = {
                node("test") {
                    run_integration_other_test('mysqltest')
                }
            }

            tests["Integration GORM Test"] = {
                node("test") {
                    run_integration_other_test('gormtest')
                }
            }

            tests["Integration Go SQL Test"] = {
                node("test") {
                    run_integration_other_test('gosqltest')
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
                    changeLogText += "\n" + commitId.take(7) + " - " + commitMsg
                }
            }
            return changeLogText
        }
        def changelog = getChangeLogText()
        def duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000
        def slackmsg = "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}" + "${changelog}" + "\n" + "${env.RUN_DISPLAY_URL}"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#pd', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this

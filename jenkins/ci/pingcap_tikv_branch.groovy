def call(TIDB_TEST_BRANCH, TIDB_BRANCH, PD_BRANCH) {

    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.GOROOT = "/usr/local/go"
    env.GOPATH = "/go"
    env.PATH = "/home/jenkins/.cargo/bin:${env.GOROOT}/bin:/home/jenkins/bin:/bin:${env.PATH}"
    env.LIBRARY_PATH = "/usr/local/lib:${env.LIBRARY_PATH}"
    env.LD_LIBRARY_PATH = "/usr/local/lib:${env.LD_LIBRARY_PATH}"

    catchError {
        stage('Prepare') {
            node('centos7_build') {
                def ws = pwd()

                // tikv
                dir("go/src/github.com/pingcap/tikv") {
                    // checkout
                    checkout scm
                    // build
                    sh """
                    rustup override set $RUST_TOOLCHAIN_BUILD
                    CARGO_TARGET_DIR=/home/jenkins/.target make release
                    """
                }
                stash includes: "go/src/github.com/pingcap/tikv/**", name: "tikv"

                // tidb
                dir("go/src/github.com/pingcap/tidb") {
                    // checkout
                    git changelog: false, credentialsId: 'github-iamxy-ssh', poll: false, url: 'git@github.com:pingcap/tidb.git', branch: "${TIDB_BRANCH}"
                    sh "GOPATH=${ws}/go:$GOPATH make parser"
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb/${TIDB_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"
                }
                stash includes: "go/src/github.com/pingcap/tidb/**", name: "tidb"

                // tidb-test
                dir("go/src/github.com/pingcap/tidb-test") {
                    // checkout
                    git changelog: false, credentialsId: 'github-iamxy-ssh', poll: false, url: 'git@github.com:pingcap/tidb-test.git', branch: "${TIDB_TEST_BRANCH}"
                }
                stash includes: "go/src/github.com/pingcap/tidb-test/**", name: "tidb-test"
            }

            // pd
            def pd_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/pd/${PD_BRANCH}/centos7/sha1").trim()
            sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"

            unstash 'tikv'
            sh "cp go/src/github.com/pingcap/tikv/bin/tikv-server bin/tikv-server && rm -rf go/src/github.com/pingcap/tikv"

            stash includes: "bin/**", name: "binaries"
        }

        stage('Test') {
            def tests = [:]

            tests["TiKV Test"] = {
                node("test") {
                    deleteDir()
                    unstash 'tikv'

                    dir("go/src/github.com/pingcap/tikv") {
                        sh """
                        rustup override set $RUST_TOOLCHAIN_TEST
                        make test
                        """
                    }
                }
            }

            tests["Integration DDL Insert Test"] = {
                node("test") {
                    def run_integration_ddl_test_1 = { ddltest ->
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_ddl_test.log &
                            sleep 40
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
                    run_integration_ddl_test_1('TestDDLSuite.TestSimple.*Insert')
                }
            }

            tests["Integration DDL Update Test"] = {
                node("test") {
                    def run_integration_ddl_test_2 = { ddltest ->
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_ddl_test.log &
                            sleep 40
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
                    run_integration_ddl_test_2('TestDDLSuite.TestSimple.*Update')
                }
            }

            tests["Integration DDL Delete Test"] = {
                node("test") {
                    def run_integration_ddl_test_3 = { ddltest ->
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_ddl_test.log &
                            sleep 40
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
                    run_integration_ddl_test_3('TestDDLSuite.TestSimple.*Delete')
                }
            }

            tests["Integration DDL Other Test"] = {
                node("test") {
                    def run_integration_ddl_test_4 = { ddltest ->
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_ddl_test.log &
                            sleep 40
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
                    run_integration_ddl_test_4('TestDDLSuite.TestSimp(le\$|leMixed|leInc)')
                }
            }

            tests["Integration DDL Column and Index Test"] = {
                node("test") {
                    def run_integration_ddl_test_5 = { ddltest ->
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_ddl_test.log &
                            sleep 40
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
                    run_integration_ddl_test_5('TestDDLSuite.Test(Column|Index)')
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
                        sleep 20
                        bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_conntest.log &
                        sleep 40
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

            tests["Integration TiDB Test"] = {
                node('test') {
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                            sleep 40
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
                    run_integration_other_test('tidbtest')
                }
            }

            tests["Integration MySQL Test"] = {
                node("test") {
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                            sleep 40
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
                    run_integration_other_test('mysqltest')
                }
            }

            tests["Integration GORM Test"] = {
                node("test") {
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                            sleep 40
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
                    run_integration_other_test('gormtest')
                }
            }

            tests["Integration Go SQL Test"] = {
                node("test") {
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
                            sleep 20
                            bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                            sleep 40
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
            slackSend channel: '#kv', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this

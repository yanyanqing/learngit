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

    catchError {
        stage('SCM Checkout') {
            // pd
            dir("${pd_path}") {
                git credentialsId: 'github-liuyin', url: 'git@github.com:pingcap/pd.git'
                githash_pd = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }

            // tidb
            dir("${tidb_path}") {
                git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb.git'
            }

            // tidb_test
            dir("${tidb_test_path}") {
                git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb-test.git'
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
                    dir ("${pd_path}") {
                        git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/pd.git'
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
                }
            }

            parallel branches
        }

        stage('Stash') {
            sh """
            rm -rf release

            # pd
            mkdir -p release/pd/bin/${platform} release/pd/conf release/pd/src
            cp ${pd_path}/bin/pd-server release/pd/bin/${platform}/
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
            branches["Integration DDL Insert Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        timeout(10) {
                            sh """
                            cp ${tidb_path}/bin/tidb-server ${tidb_test_path}/ddl_test/ddltest_tidb-server
                            cd ${tidb_test_path}/ddl_test && ./run-tests.sh -check.f='TestDDLSuite.TestSimple.*Insert'
                            """
                        }
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration DDL Update Test"] = {
                node('worker-high') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        timeout(10) {
                            sh """
                            cp ${tidb_path}/bin/tidb-server ${tidb_test_path}/ddl_test/ddltest_tidb-server
                            cd ${tidb_test_path}/ddl_test && ./run-tests.sh -check.f='TestDDLSuite.TestSimple.*Update'
                            """
                        }
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration DDL Delete Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        timeout(10) {
                            sh """
                            cp ${tidb_path}/bin/tidb-server ${tidb_test_path}/ddl_test/ddltest_tidb-server
                            cd ${tidb_test_path}/ddl_test && ./run-tests.sh -check.f='TestDDLSuite.TestSimple.*Delete'
                            """
                        }
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration DDL Other Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        timeout(10) {
                            sh """
                            cp ${tidb_path}/bin/tidb-server ${tidb_test_path}/ddl_test/ddltest_tidb-server
                            cd ${tidb_test_path}/ddl_test && ./run-tests.sh -check.f='TestDDLSuite.TestSimp(le\$|leMixed|leInc)'
                            """
                        }
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration DDL Column and Index Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        timeout(10) {
                            sh """
                            cp ${tidb_path}/bin/tidb-server ${tidb_test_path}/ddl_test/ddltest_tidb-server
                            cd ${tidb_test_path}/ddl_test && ./run-tests.sh -check.f='TestDDLSuite.Test(Column|Index)'
                            """
                        }
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration Connection Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        sh "cd ${tidb_path} && go test --args with-tikv store/tikv/*.go"
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration TiDB Test"] = {
                node('worker-high') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        sh "cd ${tidb_test_path} && TIKV_PATH='127.0.0.1:2379' TIDB_TEST_STORE_NAME=tikv make tidbtest"
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration MySQL Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        sh "cd ${tidb_test_path} && TIKV_PATH='127.0.0.1:2379' TIDB_TEST_STORE_NAME=tikv make mysqltest"
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration GORM Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        sh "cd ${tidb_test_path} && TIKV_PATH='127.0.0.1:2379' TIDB_TEST_STORE_NAME=tikv make gormtest"
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }
            branches["Integration Go SQL Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash "release-pd-${platform}"
                    unstash "release-tikv-${platform}"

                    try {
                        sh """
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        sleep 5
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        sleep 5
                        """

                        sh "cd ${tidb_test_path} && TIKV_PATH='127.0.0.1:2379' TIDB_TEST_STORE_NAME=tikv make gosqltest"
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }

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


    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#pd', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "" +
                "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${currentBuild.duration}, " +
                "${currentBuild.changeSets}" +
                "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"
    } else {
        build job: 'TIDB_LATEST_PUBLISH', wait: false
    }
}

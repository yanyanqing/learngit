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

    catchError {
        stage('SCM Checkout') {
            // tikv
            dir("${tikv_path}") {
                git credentialsId: 'github-liuyin', url: 'git@github.com:pingcap/tikv.git'
                githash_tikv = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
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
                // tikv
                sh """
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
                        git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tikv.git'
                    }

                    sh """
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
                node('worker-tikvtest') {
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    rustup default nightly-2016-10-06
                    cd ${tikv_path}
                    make test
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
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        #sleep 5
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        #sleep 5
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
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        #sleep 5
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        #sleep 5
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
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        #sleep 5
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        #sleep 5
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
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        #sleep 5
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        #sleep 5
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
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                        #sleep 5
                        #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                        #sleep 5
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
            def target = "${binary}/tikv/${githash_tikv}"
            sh """
            rm -rf ${target} && mkdir -p ${target}
            cp -R release/tikv/* ${target}/
            ln -sfT ${target} ${binary}/tikv_latest
            """
        }

        currentBuild.result = "SUCCESS"
    }

    def changeLogSets = currentBuild.changeSets
    def changeLogText = ""
    for (int i = 0; i < changeLogSets.size(); i++) {
        def entries = changeLogSets[i].items
        for (int j = 0; j < entries.length; j++) {
            def entry = entries[j]
            changeLogText += "\n${entry.commitId} by ${entry.author} on ${new Date(entry.timestamp)}: ${entry.msg}"
        }
    }

    def duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#kv', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "" +
                "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}, " +
                "${changeLogText}" + "\n"
                "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"
    } else {
        slackSend channel: '#kv', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "" +
                "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}, " +
                "${changeLogText}" + "\n"
                "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"

        build job: 'TIDB_LATEST_PUBLISH', wait: false
    }
}

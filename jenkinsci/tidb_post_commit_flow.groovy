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

            branches["Unit Test"] = {
                node('worker') {
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    rm -rf ${pingcap}/vendor
                    cd ${tidb_path} && make race
                    """
                }
            }
            branches["TiDB Test"] = {
                node('worker') {        // TiDB Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh "cd ${tidb_test_path} && make tidbtest"
                }
            }
            branches["MySQL Test"] = {
                node('worker') {        // MySQL Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh "cd ${tidb_test_path} && make mysqltest"
                }
            }
//            branches["XORM Test"] = {
//                node('worker') {        // XORM Test
//                    deleteDir()
//                    unstash 'source-pingcap'
//                    sh "cd ${tidb_test_path} && make xormtest"
//                }
//            }
            branches["GORM Test"] = {
                node('worker') {        // GORM Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh "cd ${tidb_test_path} && make gormtest"
                }
            }
            branches["Go SQL Test"] = {
                node('worker') {        // Go SQL Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh "cd ${tidb_test_path} && make gosqltest"
                }
            }
            branches["Mybaits Test"] = {
                node('worker') {        // Mybatis3 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    unstash 'source-mybatis3'

                    sh """
                    killall -9 tidb-server || true
                    ${tidb_path}/bin/tidb-server --store memory -join-concurrency=1 > tidb_mybatis3_test.log 2>&1 &
                    sleep 3
                    """

                    try {
                        sh "mvn -B -f ${workspace}/mybatis3/pom.xml clean test"
                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 tidb-server || true"
                    }
                }
            }
            branches["SQLLogic Random Aggregates Test"] = {
                node('worker-high') {        // SQLLogic Random Aggregates Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/random/aggregates' \
                    TIDB_PARALLELISM=12 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Random Expr Test"] = {
                node('worker') {        // SQLLogic Random Expr Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/random/expr' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Random Groupby Test"] = {
                node('worker') {        // SQLLogic Random Groupby Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/random/groupby' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Random Select Test"] = {
                node('worker-high') {        // SQLLogic Random Select Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/random/select' \
                    TIDB_PARALLELISM=10 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Select Test"] = {
                node('worker') {        // SQLLogic Select Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/select' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index Between Test"] = {
                node('worker') {        // SQLLogic Index Between Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/between' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index commute 10 Test"] = {
                node('worker') {        // SQLLogic Index commute Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/commute/10' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index commute 100 Test"] = {
                node('worker') {        // SQLLogic Index commute 100 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/commute/100' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index commute 1000 Test"] = {
                node('worker') {        // SQLLogic Index commute 1000 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/commute/1000' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index delete 1 Test"] = {
                node('worker') {        // SQLLogic Index delete 1 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/delete/1' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index delete 10 Test"] = {
                node('worker') {        // SQLLogic Index delete 10 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/delete/10' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index delete 100 Test"] = {
                node('worker') {        // SQLLogic Index delete 100 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/delete/100' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index delete 1000 Test"] = {
                node('worker') {        // SQLLogic Index delete 1000 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/delete/1000' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index delete 10000 Test"] = {
                node('worker') {        // SQLLogic Index delete 10000 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/delete/10000' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index in 10 Test"] = {
                node('worker') {        // SQLLogic Index in 10 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/in/10' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index in 100 Test"] = {
                node('worker') {        // SQLLogic Index in 100 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/in/100' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index in 1000 Test"] = {
                node('worker-high') {        // SQLLogic Index in 1000 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/in/1000' \
                    TIDB_PARALLELISM=8 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index orderby 10 Test"] = {
                node('worker') {        // SQLLogic Index orderby 10 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/orderby/10' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index orderby 100 Test"] = {
                node('worker') {        // SQLLogic Index orderby 100 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/orderby/100' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index orderby 1000 Test"] = {
                node('worker') {        // SQLLogic Index orderby 1000 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/orderby/1000' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index orderby_nosort 10 Test"] = {
                node('worker-high') {        // SQLLogic Index orderby_nosort 10 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/orderby_nosort/10' \
                    TIDB_PARALLELISM=8 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index orderby_nosort 100 Test"] = {
                node('worker') {        // SQLLogic Index orderby_nosort 100 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/orderby_nosort/100' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index orderby_nosort 1000 Test"] = {
                node('worker') {        // SQLLogic Index orderby_nosort 1000 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/orderby_nosort/1000' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index random 10 Test"] = {
                node('worker') {        // SQLLogic Index random 10 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/random/10' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index random 100 Test"] = {
                node('worker') {        // SQLLogic Index random 100 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/random/100' \
                    TIDB_PARALLELISM=4 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
                    """
                }
            }
            branches["SQLLogic Index random 1000 Test"] = {
                node('worker-high') {        // SQLLogic Index random 1000 Test
                    deleteDir()
                    unstash 'source-pingcap'
                    sh """
                    cd ${tidb_test_path}
                    SQLLOGIC_TEST_PATH='/home/pingcap/sqllogictest/test/index/random/1000' \
                    TIDB_PARALLELISM=8 \
                    TIDB_SERVER_PATH=${tidb_path}/bin/tidb-server \
                    make sqllogictest
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
        slackSend channel: '#tidb', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
        build job: 'TIDB_LATEST_PUBLISH', wait: false
    }
}

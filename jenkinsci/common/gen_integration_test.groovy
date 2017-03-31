def call(branches, platform, tidb_path, tidb_test_path) {
    branches["Integration DDL Insert Test"] = {
        node('worker') {
            deleteDir()
            unstash 'source-pingcap'
            unstash "release-pd-${platform}"
            unstash "release-tikv-${platform}"

            try {
                sh """
                killall -9 ddltest_tidb-server || true
                killall -9 pd-server || true
                release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                #sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                #sleep 10
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
                sh "killall -9 ddltest_tidb-server || true"
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
                killall -9 ddltest_tidb-server || true
                killall -9 pd-server || true
                release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                #sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                #sleep 10
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
                sh "killall -9 ddltest_tidb-server || true"
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
                killall -9 ddltest_tidb-server || true
                killall -9 pd-server || true
                release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                #sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                #sleep 10
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
                sh "killall -9 ddltest_tidb-server || true"
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
                killall -9 ddltest_tidb-server || true
                killall -9 pd-server || true
                release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                #sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                #sleep 10
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
                sh "killall -9 ddltest_tidb-server || true"
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
                killall -9 ddltest_tidb-server || true
                killall -9 pd-server || true
                release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                #sleep 10
                #release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                #sleep 10
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
                sh "killall -9 ddltest_tidb-server || true"
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
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                sleep 10
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
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                sleep 10
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
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                sleep 10
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
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                sleep 10
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
                sleep 10
                killall -9 tikv-server || true
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_1_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data2 --addr=0.0.0.0:20161 --advertise-addr=127.0.0.1:20161 &>tikv_2_test.log &
                sleep 10
                release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s data3 --addr=0.0.0.0:20162 --advertise-addr=127.0.0.1:20162 &>tikv_3_test.log &
                sleep 10
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
}

return this

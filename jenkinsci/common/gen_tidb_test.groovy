def call(branches) {
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
//    branches["XORM Test"] = {
//        node('worker') {        // XORM Test
//            deleteDir()
//            unstash 'source-pingcap'
//            sh "cd ${tidb_test_path} && make xormtest"
//        }
//    }
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
}

return this
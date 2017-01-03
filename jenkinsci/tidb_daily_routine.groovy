#!groovy

node('master') {
    def workspace = pwd()
    env.GOPATH = "${workspace}/go"
    env.GOROOT = "/usr/local/go"
    env.PATH = "${workspace}/go/bin:${env.GOROOT}/bin:${env.PATH}"
    def pingcap = "${workspace}/go/src/github.com/pingcap"
    def tidb_test_path = "${pingcap}/tidb-test"
    def cluster_test_path = "${tidb_test_path}/cluster_test"
    def tidb_tools_path = "${pingcap}/tidb-tools"

    catchError {
        stage('SCM Checkout') {
            // tidb_test
            dir("${tidb_test_path}") {
                git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb-test.git'
            }

            // tidb_tools
            dir("${tidb_tools_path}") {
                git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb-tools.git'
            }

            withEnv(["http_proxy=${proxy}", "https_proxy=${proxy}"]) {
                retry(3) {
                    sh """
                    go get -d -u github.com/go-sql-driver/mysql
                    go get -d -u github.com/juju/errors
                    go get -d -u github.com/ngaut/log
                    go get -d -u github.com/satori/go.uuid
                    """
                }
            }
        }

        stage('Build') {
            sh """
            cd ${tidb_test_path} && make clustertest
            cd ${tidb_tools_path} && make importer
            """
        }

        stage("Big Transaction Test") {
            try {
                sh """
                docker-compose -f ${cluster_test_path}/docker/docker-compose.yml -p bigtxntest up -d
                sleep 120
                host_port=`docker-compose -f ${cluster_test_path}/docker/docker-compose.yml -p bigtxntest port haproxy 4000 | awk -F\':\' \'{print \$2}\'`
                ${tidb_tools_path}/bin/importer -P \$host_port -b 100 -c 20 -n 2000000 -t \'create table t(a int primary key, b double, c varchar(10));\'
                mysql -uroot -h127.0.0.1 -P\$host_port -e \'drop table test.t\'
                """
            } catch (err) {
                throw err
            } finally {
                sh "docker-compose -f ${cluster_test_path}/docker/docker-compose.yml -p bigtxntest down"
            }
        }

        stage('Nemesis Transaction Test') {
            try {
                sh """
                cd ${cluster_test_path}
                ./cluster_test --compose-file=docker/docker-compose.yml --auto-destory --count=100000 --filter-pd --doze-time=30
                """
            } catch (err) {
                throw err
            } finally {
                sh "docker-compose -f ${cluster_test_path}/docker/docker-compose.yml -p clustertest down"
            }
        }

        currentBuild.result = "SUCCESS"
    }

    def duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#dt', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "" +
                "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}, " +
                "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"
    }
}
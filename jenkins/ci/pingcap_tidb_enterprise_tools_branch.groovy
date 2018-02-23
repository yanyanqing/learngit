def call(TIDB_BINLOG_BRANCH,TIDB_TOOLS_BRANCH,TIDB_BRANCH) {
    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.GOROOT = "/usr/local/go"
    env.GOPATH = "/go"
    env.PATH = "${env.GOROOT}/bin:/home/jenkins/bin:/bin:${env.PATH}"

    catchError {
        stage('Prepare') {
            node('centos7_build') {
                def ws = pwd()

                // tidb-enterprise-tools
                dir("go/src/github.com/pingcap/tidb-enterprise-tools") {
                    // checkout
                    checkout scm
                    // build
                    sh "GOPATH=${ws}/go:$GOPATH make syncer"
                    sh "GOPATH=${ws}/go:$GOPATH make loader"
                }
                stash includes: "go/src/github.com/pingcap/tidb-enterprise-tools/**", name: "tidb-enterprise-tools"

                // tidb-binlog 
                dir("go/src/github.com/pingcap/tidb-binlog") {
                    // checkout 
                    git changelog: false, credentialsId: 'github-iamxy-ssh', poll: false, url: 'git@github.com:pingcap/tidb-binlog.git', branch: "${TIDB_BINLOG_BRANCH}"
                    sh "GOPATH=${ws}/go:$GOPATH make diff"
                }
                stash includes: "go/src/github.com/pingcap/tidb-binlog/**", name: "diff"

                dir("importer") {
                    // download importer
                    tidb_tools_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb-tools/${TIDB_TOOLS_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz | tar xz"
                }
                // importer/bin/importer
                stash includes: "importer/**", name: "importer"

                dir("mydumper") {
                    // download mydumper 
                    sh "curl -L ${UCLOUD_OSS_URL}/tools/mydumper-linux-amd64.tar.gz -o mydumper.tar.gz && tar zxf mydumper.tar.gz && rm -f mydumper.tar.gz"
                    sh "cp -R mydumper-linux-amd64/* . && rm -rf mydumper-linux-amd64"
                }
                // mydumper/bin/mydumper
                stash includes: "mydumper/**", name: "mydumper"

                dir("tidb") {
                    // download tidb binary 
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb/${TIDB_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"

                }
                // tidb/bin/tidb-server 
                stash includes: "tidb/**", name: "tidb"
            }
        }

        stage('Test') {
            def tests = [:]

            tests["Unit Test"] = {
                node("test") {
                    def ws = pwd()
                    def nodename = "${env.DOCKER_HOST}"
                    def HOSTIP = nodename.getAt(6..(nodename.lastIndexOf(':') - 1))

                    deleteDir()
                    unstash 'tidb-enterprise-tools'

                    docker.withServer("${env.DOCKER_HOST}") {
                        docker.image('mysql:5.6').withRun('-p 3306:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=1', '--log-bin --binlog-format=ROW --server-id=1') { c ->
                            dir("go/src/github.com/pingcap/tidb-enterprise-tools") {
                                sh "GOPATH=${ws}/go:$GOPATH MYSQL_HOST=${HOSTIP} make test"
                            }
                        }
                    }
                }
            }

            tests["Loader Test"] = {
                node("test") {
                    def ws = pwd()
                    def nodename = "${env.DOCKER_HOST}"
                    def HOSTIP = nodename.getAt(6..(nodename.lastIndexOf(':') - 1))

                    deleteDir()
                    unstash "tidb-enterprise-tools"
                    unstash "importer"
                    unstash "mydumper"
                    unstash "diff"
                    unstash "tidb"

                    // start mysql-server 
                    docker.withServer("${env.DOCKER_HOST}") {
                        docker.image("mysql:5.6").withRun('-p 3308:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=1', '--log-bin --binlog-format=ROW --server-id=1') { c ->
                            dir("go/src/github.com/pingcap/tidb-enterprise-tools") {
                                sh """
                                export MYSQL_HOST=${HOSTIP} 
                                export MYSQL_PORT=3308 
                                export ws=${ws} 
                                export TIDB_DIR="${ws}/tidb" 
                                export IMPORTER_DIR="${ws}/importer" 
                                export MYDUMPER_DIR="${ws}/mydumper" 
                                export LOADER_DIR="${ws}/go/src/github.com/pingcap/tidb-enterprise-tools" 
                                export DIFF_DIR="${ws}/go/src/github.com/pingcap/tidb-binlog" 
                                cd tests && sh -x ./loader_sharding_test.sh 
                                """
                            }
                        }
                    }
                }
            }
            
            tests["Syncer Test"] = {
                node("test") {
                    def ws = pwd()
                    def nodename = "${env.DOCKER_HOST}"
                    def HOSTIP = nodename.getAt(6..(nodename.lastIndexOf(':') - 1))

                    deleteDir()
                    unstash "tidb-enterprise-tools"
                    unstash "importer"
                    unstash "mydumper"
                    unstash "diff"
                    unstash "tidb"

                    // start mysql-server 
                    docker.withServer("${env.DOCKER_HOST}") {
                        docker.image("mysql:5.6").withRun('-p 3310:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=1', '--log-bin --binlog-format=ROW --server-id=1') { c ->
                            dir("go/src/github.com/pingcap/tidb-enterprise-tools") {
                                sh """
                                export MYSQL_HOST=${HOSTIP} 
                                export MYSQL_PORT=3310 
                                export ws=${ws} 
                                export TIDB_DIR="${ws}/tidb" 
                                export IMPORTER_DIR="${ws}/importer" 
                                export MYDUMPER_DIR="${ws}/mydumper" 
                                export SYNCER_DIR="${ws}/go/src/github.com/pingcap/tidb-enterprise-tools" 
                                export DIFF_DIR="${ws}/go/src/github.com/pingcap/tidb-binlog" 
                                export STATUS_PORT=10081  
                                cd tests &&  sh -x ./syncer_sharding_test.sh 
                                """
                            }
                        }
                    }
                }
            }

            tests["Syncer GTID Test"] = {
                node("test") {
                    def ws = pwd()
                    def nodename = "${env.DOCKER_HOST}"
                    def HOSTIP = nodename.getAt(6..(nodename.lastIndexOf(':') - 1))

                    deleteDir()
                    unstash "tidb-enterprise-tools"
                    unstash "importer"
                    unstash "mydumper"
                    unstash "diff"
                    unstash "tidb"

                    // start mysql-server 
                    docker.withServer("${env.DOCKER_HOST}") {
                        docker.image("mysql:5.6").withRun('-p 3311:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=1', '--log-bin --binlog-format=ROW --server-id=1 --gtid_mode=ON --enforce-gtid-consistency  --log-slave-updates') { c ->
                            dir("go/src/github.com/pingcap/tidb-enterprise-tools") {
                                sh """
                                export MYSQL_HOST=${HOSTIP} 
                                export MYSQL_PORT=3311 
                                export ws=${ws} 
                                export TIDB_DIR="${ws}/tidb" 
                                export IMPORTER_DIR="${ws}/importer" 
                                export MYDUMPER_DIR="${ws}/mydumper" 
                                export SYNCER_DIR="${ws}/go/src/github.com/pingcap/tidb-enterprise-tools" 
                                export DIFF_DIR="${ws}/go/src/github.com/pingcap/tidb-binlog" 
                                export STATUS_PORT=10081  
                                cd tests && sh -x ./syncer_gtid_test.sh 
                                """
                            }
                        }
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
        def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration}` Mins" +
        "${changelog}" + "\n" +
        "${env.RUN_DISPLAY_URL}"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#tidb-tools', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
        echo "summary finished"
    }
}

return this

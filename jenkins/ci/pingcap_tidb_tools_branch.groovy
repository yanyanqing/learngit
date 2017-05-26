def call() {

    env.GOROOT = "/usr/local/go"
    env.GOPATH = "/go"
    env.PATH = "${env.GOROOT}/bin:/home/jenkins/bin:/bin:${env.PATH}"

    catchError {
        stage('Prepare') {
            node('centos7_build') {
                def ws = pwd()

                // tidb-tools
                dir("go/src/github.com/pingcap/tidb-tools") {
                    // checkout
                    checkout scm
                    // build
                    sh "GOPATH=${ws}/go:$GOPATH make importer"
                    sh "GOPATH=${ws}/go:$GOPATH make syncer"
                    sh "GOPATH=${ws}/go:$GOPATH make checker"
                    sh "GOPATH=${ws}/go:$GOPATH make loader"
                }
                stash includes: "go/src/github.com/pingcap/tidb-tools/**", name: "tidb-tools"
            }
        }

        stage('Test') {
            def tests = [:]

            tests["Unit Test"] = {
                node("test") {
                    def nodename = "${env.NODE_NAME}"
                    def HOSTIP = nodename.getAt(8..(nodename.lastIndexOf('-') - 1))
                    def ws = pwd()
                    deleteDir()
                    unstash 'tidb-tools'

                    docker.withServer("tcp://${HOSTIP}:32376") {
                        docker.image('mysql:5.6').withRun('-p 3306:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=1') { c ->
                            dir("go/src/github.com/pingcap/tidb-tools") {
                                sh "GOPATH=${ws}/go:$GOPATH MYSQL_HOST=${HOSTIP} make test"
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

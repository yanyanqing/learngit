def call(TIDB_BRANCH, TIKV_BRANCH, PD_BRANCH) {

    env.GOROOT = "/usr/local/go"
    env.GOPATH = "/go"
    env.PATH = "${env.GOROOT}/bin:/home/jenkins/bin:/bin:${env.PATH}"

    catchError {
        stage('Prepare') {
            node('centos7_build') {
                def ws = pwd()

                // goleveldb
                dir("go/src/github.com/pingcap/goleveldb") {
                    git changelog: false, credentialsId: 'github-iamxy-ssh', poll: false, url: 'git@github.com:pingcap/goleveldb.git'
                }

                // other deps
                sh """
                GOPATH=${ws}/go:$GOPATH go get -d -u github.com/BurntSushi/toml
                GOPATH=${ws}/go:$GOPATH go get -d -u github.com/go-sql-driver/mysql
                GOPATH=${ws}/go:$GOPATH go get -d -u github.com/juju/errors
                GOPATH=${ws}/go:$GOPATH go get -d -u github.com/ngaut/log
                GOPATH=${ws}/go:$GOPATH go get -d -u github.com/golang/snappy
                GOPATH=${ws}/go:$GOPATH go get -d -u github.com/petar/GoLLRB/llrb
                GOPATH=${ws}/go:$GOPATH go get -d -u golang.org/x/text
                GOPATH=${ws}/go:$GOPATH go get -d -u golang.org/x/net/context
                """

                // tidb-binlog
                dir("go/src/github.com/pingcap/tidb-binlog") {
                    // checkout
                    checkout scm

                    // build
                    sh "GOPATH=${ws}/go:$GOPATH make"

                    dir("test") {
                        sh "GOPATH=${ws}/go:$GOPATH go build"
                    }
                }
                stash includes: "go/src/github.com/**", name: "tidb-binlog"
            }

            // tidb
            def tidb_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb/${TIDB_BRANCH}/centos7/sha1").trim()
            sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"

            // tikv
            def tikv_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tikv/${TIKV_BRANCH}/centos7/sha1").trim()
            sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"

            // pd
            def pd_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/pd/${PD_BRANCH}/centos7/sha1").trim()
            sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"

            stash includes: "bin/**", name: "binaries"
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

def call(BUILD_BRANCH) {

    def BUILD_URL = 'git@github.com:pingcap/tidb-enterprise-tools.git'
    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.GOROOT = "/usr/local/go"
    env.GOPATH = "/go"
    env.PATH = "${env.GOROOT}/bin:/home/jenkins/bin:/bin:${env.PATH}"
    def githash_centos7

    catchError {
        stage('Build') {
            def builds = [:]

            builds["linux-amd64-centos7"] = {
                node('centos7_build') {
                    def ws = pwd()
                    dir("${ws}/go/src/github.com/pingcap/tidb-enterprise-tools") {
                        // checkout scm
                        git credentialsId: 'github-iamxy-ssh', url: "$BUILD_URL", branch: "${BUILD_BRANCH}"
                        githash_centos7 = sh(returnStdout: true, script: "git rev-parse HEAD").trim()

                        // build
                        sh "GOPATH=${ws}/go:$GOPATH make syncer"
                        sh "GOPATH=${ws}/go:$GOPATH make loader"

                        // upload binary
                        sh """
                        cp ~/bin/config.cfg ./
                        tar czvf tidb-enterprise-tools.tar.gz bin/*
                        filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key builds/pingcap/tidb-enterprise-tools/${githash_centos7}/centos7/tidb-enterprise-tools.tar.gz --file tidb-enterprise-tools.tar.gz
                        """

                        // update refs
                        writeFile file: 'sha1', text: "${githash_centos7}"
                        sh "filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key refs/pingcap/tidb-enterprise-tools/${BUILD_BRANCH}/centos7/sha1 --file sha1"

                        // cleanup
                        sh "rm -rf sha1 tidb-enterprise-tools.tar.gz config.cfg"
                    }
                }
            }

            parallel builds
        }

        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration}` Mins" + "\n" +
        "Build Branch: `${BUILD_BRANCH}`, Githash: `${githash_centos7.take(7)}`" + "\n" +
        "Binary Download URL:" + "\n" +
        "${UCLOUD_OSS_URL}/builds/pingcap/tidb-enterprise-tools/${githash_centos7}/centos7/tidb-enterprise-tools.tar.gz"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#iamgroot', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        } else {
            slackSend channel: '#iamgroot', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this

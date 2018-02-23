def call(TIDB_BINLOG_BRANCH, RELEASE_TAG) {

    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.PATH = "/home/jenkins/bin:/bin:${env.PATH}"
    def tidb_binlog_sha1

    catchError {
        node('delivery') {
            stage('Prepare') {
                deleteDir()

                dir ('centos7') {
                    tidb_binlog_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb-binlog/${TIDB_BINLOG_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb-binlog/${tidb_binlog_sha1}/centos7/tidb-binlog.tar.gz | tar xz"
                }
            }

            stage('Push Centos7 Binary') {
                def target = "tidb-binlog-${RELEASE_TAG}-linux-amd64"

                dir("${target}") {
                    sh "cp -R ../centos7/bin ./"
                }

                sh """
                tar czvf ${target}.tar.gz ${target}
                sha256sum ${target}.tar.gz > ${target}.sha256
                md5sum ${target}.tar.gz > ${target}.md5
                """

                sh """
                export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                upload.py ${target}.tar.gz ${target}.tar.gz
                upload.py ${target}.sha256 ${target}.sha256
                upload.py ${target}.md5 ${target}.md5
                """
            }

            stage('Push tidb-binlog Docker') {
                dir('tidb_binlog_docker_build') {
                    sh  """
                    cp ../centos7/bin/* ./
                    cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY pump /pump
COPY drainer /drainer
EXPOSE 4000
EXPOSE 8249 8250
CMD ["/pump"]
__EOF__
                    """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/tidb-binlog:${RELEASE_TAG}", "tidb_binlog_docker_build").push()
                }
            }
        }

        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration}` Mins" + "\n" +
        "tidb-binlog Branch: `${TIDB_BINLOG_BRANCH}`, Githash: `${tidb_binlog_sha1.take(7)}`" + "\n" +
        "tidb-binlog Binary Download URL:" + "\n" +
        "http://download.pingcap.org/tidb-binlog-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
        "tidb-binlog Binary sha256   URL:" + "\n" +
        "http://download.pingcap.org/tidb-binlog-${RELEASE_TAG}-linux-amd64.sha256" + "\n" +
        "tidb-binlog Docker Image: `pingcap/tidb-binlog:${RELEASE_TAG}`"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        } else {
            slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this

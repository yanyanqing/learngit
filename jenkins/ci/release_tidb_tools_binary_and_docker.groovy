def call(TIDB_TOOLS_BRANCH, RELEASE_TAG) {

    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.PATH = "/home/jenkins/bin:/bin:${env.PATH}"
    def tidb_tools_sha1

    catchError {
        node('delivery') {
            def nodename = "${env.NODE_NAME}"
            def HOSTIP = nodename.getAt(7..(nodename.lastIndexOf('-') - 1))

            stage('Prepare') {
                deleteDir()

                dir ('centos7') {
                    tidb_tools_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb-tools/${TIDB_TOOLS_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz | tar xz"
                }
            }

            stage('Push Centos7 Binary') {
                def target = "tidb-tools-${RELEASE_TAG}-linux-amd64"

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

            stage('Push tidb-tools Docker') {
                dir('tidb_tools_docker_build') {
                    sh  """
                    cp ../centos7/bin/* ./
                    cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
RUN apk add --no-cache mysql-client
COPY importer /importer
COPY checker /checker
COPY dump_region /dump_region
CMD ["/checker"]
__EOF__
                    """
                }

                withDockerServer([uri: "tcp://${HOSTIP}:32376"]) {
                    docker.build("pingcap/tidb-tools:${RELEASE_TAG}", "tidb_tools_docker_build").push()
                }
            }
        }

        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration}` Mins" + "\n" +
        "tidb-tools Branch: `${TIDB_TOOLS_BRANCH}`, Githash: `${tidb_tools_sha1.take(7)}`" + "\n" +
        "tidb-tools Binary Download URL:" + "\n" +
        "http://download.pingcap.org/tidb-tools-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
        "tidb-tools Binary sha256   URL:" + "\n" +
        "http://download.pingcap.org/tidb-tools-${RELEASE_TAG}-linux-amd64.sha256" + "\n" +
        "tidb-tools Docker Image: `pingcap/tidb-tools:${RELEASE_TAG}`"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        } else {
            slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this

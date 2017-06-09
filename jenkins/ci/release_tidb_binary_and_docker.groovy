def call(TIDB_BRANCH, TIKV_BRANCH, PD_BRANCH, RELEASE_TAG) {

    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.PATH = "/home/jenkins/bin:/bin:${env.PATH}"
    def tidb_sha1, tikv_sha1, pd_sha1

    catchError {
        node('delivery') {
            def nodename = "${env.NODE_NAME}"
            def HOSTIP = nodename.getAt(7..(nodename.lastIndexOf('-') - 1))

            stage('Prepare') {
                deleteDir()

                dir ('centos7') {
                    tidb_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tidb/${TIDB_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"

                    tikv_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tikv/${TIKV_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"

                    pd_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/pd/${PD_BRANCH}/centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"
                }

                dir ('centos6') {
                    def tikv_centos6_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tikv/${TIKV_BRANCH}/centos6/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tikv/${tikv_centos6_sha1}/centos6/tikv-server.tar.gz | tar xz"
                }

                dir ('unportable_centos7') {
                    def tikv_unportable_centos7_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tikv/${TIKV_BRANCH}/unportable_centos7/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tikv/${tikv_unportable_centos7_sha1}/unportable_centos7/tikv-server.tar.gz | tar xz"
                }

                dir ('unportable_centos6') {
                    def tikv_unportable_centos6_sha1 = sh(returnStdout: true, script: "curl ${UCLOUD_OSS_URL}/refs/pingcap/tikv/${TIKV_BRANCH}/unportable_centos6/sha1").trim()
                    sh "curl ${UCLOUD_OSS_URL}/builds/pingcap/tikv/${tikv_unportable_centos6_sha1}/unportable_centos6/tikv-server.tar.gz | tar xz"
                }
            }

            stage('Push Centos7 Binary') {
                def target = "tidb-${RELEASE_TAG}-linux-amd64"

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

            stage('Push Centos6 Binary') {
                def target = "tidb-${RELEASE_TAG}-linux-amd64-centos6"

                dir("${target}") {
                    sh "cp -R ../centos7/bin ./"
                    sh "cp ../centos6/bin/tikv-server bin/"
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

            stage('Push Unportable Centos7 Binary') {
                def target = "tidb-${RELEASE_TAG}-linux-amd64-unportable"

                dir("${target}") {
                    sh "cp -R ../centos7/bin ./"
                    sh "cp ../unportable_centos7/bin/tikv-server bin/"
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

            stage('Push Unportable Centos6 Binary') {
                def target = "tidb-${RELEASE_TAG}-linux-amd64-unportable-centos6"

                dir("${target}") {
                    sh "cp -R ../centos7/bin ./"
                    sh "cp ../unportable_centos6/bin/tikv-server bin/"
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

            stage('Push tidb Docker') {
                dir('tidb_docker_build') {
                    sh  """
                    cp ../centos7/bin/tidb-server ./
                    cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY tidb-server /tidb-server
EXPOSE 4000
ENTRYPOINT ["/tidb-server"]
__EOF__
                    """
                }

                withDockerServer([uri: "tcp://${HOSTIP}:32376"]) {
                    docker.build("pingcap/tidb:${RELEASE_TAG}", "tidb_docker_build").push()
                }
            }

            stage('Push tikv Docker') {
                dir('tikv_docker_build') {
                    sh """
                    cp ../centos7/bin/tikv-server ./
                    cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
ENV TZ /etc/localtime
COPY tikv-server /tikv-server
EXPOSE 20160
ENTRYPOINT ["/tikv-server"]
__EOF__
                    """
                }

                withDockerServer([uri: "tcp://${HOSTIP}:32376"]) {
                    docker.build("pingcap/tikv:${RELEASE_TAG}", "tikv_docker_build").push()
                }
            }

/*
            stage('Push Unportable tikv Docker') {
                dir('unportable_tikv_docker_build') {
                    sh """
                    cp ../unportable_centos7/bin/tikv-server ./
                    cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
ENV TZ /etc/localtime
COPY tikv-server /tikv-server
EXPOSE 20160
ENTRYPOINT ["/tikv-server"]
__EOF__
                    """
                }

                withDockerServer([uri: "tcp://${HOSTIP}:32376"]) {
                    docker.build("pingcap/tikv:${RELEASE_TAG}-unportable", "unportable_tikv_docker_build").push()
                }
            }
 */

            stage('Push pd Docker') {
                dir('pd_docker_build') {
                    sh """
                    cp ../centos7/bin/pd-server ./
                    cp ../centos7/bin/pd-ctl ./
                    cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY pd-server /pd-server
COPY pd-ctl /pd-ctl
EXPOSE 2379 2380
ENTRYPOINT ["/pd-server"]
__EOF__
                    """
                }

                withDockerServer([uri: "tcp://${HOSTIP}:32376"]) {
                    docker.build("pingcap/pd:${RELEASE_TAG}", "pd_docker_build").push()
                }
            }
        }

        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration}` Mins" + "\n" +
        "tidb Branch: `${TIDB_BRANCH}`, Githash: `${tidb_sha1.take(7)}`" + "\n" +
        "tikv Branch: `${TIKV_BRANCH}`, Githash: `${tikv_sha1.take(7)}`" + "\n" +
        "pd   Branch: `${PD_BRANCH}`, Githash: `${pd_sha1.take(7)}`" + "\n" +
        "TiDB Binary Download URL:" + "\n" +
        "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
        "TiDB Binary sha256   URL:" + "\n" +
        "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64.sha256" + "\n" +
        "TiDB Binary (for Centos6) Download URL:" + "\n" +
        "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64-centos6.tar.gz" + "\n" +
        "TiDB Binary (for Centos6) SHA256   URL:" + "\n" +
        "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64-centos6.sha256" + "\n" +
        "TiDB Binary (unportable) Download URL:" + "\n" +
        "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64-unportable.tar.gz" + "\n" +
        "TiDB Binary (unportable) SHA256   URL:" + "\n" +
        "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64-unportable.sha256" + "\n" +
        "TiDB Binary (unportable for Centos6) Download URL:" + "\n" +
        "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64-unportable-centos6.tar.gz" + "\n" +
        "TiDB Binary (unportable for Centos6) SHA256   URL:" + "\n" +
        "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64-unportable-centos6.sha256" + "\n" +
        "tidb Docker Image: `pingcap/tidb:${RELEASE_TAG}`" + "\n" +
        "pd   Docker Image: `pingcap/pd:${RELEASE_TAG}`" + "\n" +
        "tikv Docker Image: `pingcap/tikv:${RELEASE_TAG}`"
//        "tikv Docker Image: `pingcap/tikv:${RELEASE_TAG}`" + "\n" +
//        "tikv Unportable Docker Image: `pingcap/tikv:${RELEASE_TAG}-unportable`"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        } else {
            slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this

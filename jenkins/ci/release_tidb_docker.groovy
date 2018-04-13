def call(TIDB_BRANCH, TIKV_BRANCH, PD_BRANCH, RELEASE_TAG) {

    def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
    env.PATH = "/home/jenkins/bin:/bin:${env.PATH}"
	def UCLOUD_REGISTRY = "uhub.service.ucloud.cn"
    def tidb_sha1, tikv_sha1, pd_sha1

    catchError {
        node('delivery') {
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

                dir ('tidb-operator') {
                    git credentialsId: 'github-iamxy-ssh', url: 'git@github.com:pingcap/tidb-operator.git', branch: 'master', changelog: false, poll: false
                }
            }

            stage('Push tidb Docker') {
                dir('tidb_docker_build') {
                    sh  """
                    cp ../centos7/bin/tidb-server ./
                    cp ../tidb-operator/hack/tidb/entrypoint.sh ./
                    cp ../tidb-operator/hack/tidb/Dockerfile ./
                    """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("${UCLOUD_REGISTRY}/pingcap/tidb:${RELEASE_TAG}", "tidb_docker_build").push()
                }
            }

            stage('Push tikv Docker') {
                dir('tikv_docker_build') {
                    sh """
                    cp ../centos7/bin/tikv-server ./
                    cp ../tidb-operator/hack/tikv/entrypoint.sh ./
                    cp ../tidb-operator/hack/tikv/Dockerfile ./
                    """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("${UCLOUD_REGISTRY}/pingcap/tikv:${RELEASE_TAG}", "tikv_docker_build").push()
                }
            }

            stage('Push pd Docker') {
                dir('pd_docker_build') {
                    sh """
                    cp ../centos7/bin/pd-server ./
                    cp ../centos7/bin/pd-ctl ./
                    cp ../tidb-operator/hack/pd/entrypoint.sh ./
                    cp ../tidb-operator/hack/pd/Dockerfile ./
                    """                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("${UCLOUD_REGISTRY}/pingcap/pd:${RELEASE_TAG}", "pd_docker_build").push()
                }
            }
        }

        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration}` Mins" + "\n" +
        "tidb Branch: `${TIDB_BRANCH}`, Githash: `${tidb_sha1.take(7)}`" + "\n" +
        "tikv Branch: `${TIKV_BRANCH}`, Githash: `${tikv_sha1.take(7)}`" + "\n" +
        "pd   Branch: `${PD_BRANCH}`, Githash: `${pd_sha1.take(7)}`" + "\n" +
        "tidb Docker Image: `${UCLOUD_REGISTRY}/pingcap/tidb:${RELEASE_TAG}`" + "\n" +
        "pd   Docker Image: `${UCLOUD_REGISTRY}/pingcap/pd:${RELEASE_TAG}`" + "\n" +
        "tikv Docker Image: `${UCLOUD_REGISTRY}/pingcap/tikv:${RELEASE_TAG}`"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        } else {
            slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}

return this

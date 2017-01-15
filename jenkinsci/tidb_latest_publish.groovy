#!groovy

node('master') {
    def workspace = pwd()
    def binary = "/binary_registry"
    def platform = "linux-amd64"
    def tikv_githash
    def tidb_githash
    def pd_githash

    catchError {
        stage('Publish Docker') {
            def branches = [:]

            branches['TiDB'] = {
                tidb_githash = sh(returnStdout: true, script: "cat ${binary}/tidb_latest/src/.githash").trim()
                def tidb_latest = ""
                if (fileExists("tidb_githash.latest")) {
                    tidb_latest = readFile "tidb_githash.latest"
                }
                if (tidb_githash != tidb_latest) {
                    // prepare
                    sh """
                    rm -rf tidb_build && mkdir tidb_build
                    cp ${binary}/tidb/${tidb_githash}/bin/${platform}/tidb-server tidb_build/

                    cat > tidb_build/Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY tidb-server /tidb-server
EXPOSE 4000
ENTRYPOINT ["/tidb-server"]
__EOF__
                    """

                    // build
                    def tidb_image = docker.build('pingcap/tidb', "tidb_build")

                    // push
                    retry(10) {
                        timeout(time: 10, unit: 'MINUTES') {
                            tidb_image.push()
                        }
                    }

                    writeFile file: 'tidb_githash.latest', text: "${tidb_githash}"
                }
            }

            branches['TiKV'] = {
                tikv_githash = sh(returnStdout: true, script: "cat ${binary}/tikv_latest/src/.githash").trim()
                def tikv_latest = ""
                if (fileExists("tikv_githash.latest")) {
                    tikv_latest = readFile "tikv_githash.latest"
                }
                if (tikv_githash != tikv_latest) {
                    // prepare
                    sh """
                    rm -rf tikv_build && mkdir tikv_build
                    cp ${binary}/tikv/${tikv_githash}/bin/${platform}/tikv-server tikv_build/
                    cp ${binary}/tikv/${tikv_githash}/conf/config-template.toml tikv_build/

                    cat > tikv_build/Dockerfile << __EOF__
FROM pingcap/alpine-glibc
ENV TZ /etc/localtime
COPY tikv-server /tikv-server
COPY config-template.toml /config-template.toml
EXPOSE 20160
ENTRYPOINT ["/tikv-server"]
__EOF__
                    """

                    // build
                    def tikv_image = docker.build('pingcap/tikv', "tikv_build")

                    // push
                    retry(10) {
                        timeout(time: 10, unit: 'MINUTES') {
                            tikv_image.push()
                        }
                    }

                    writeFile file: 'tikv_githash.latest', text: "${tikv_githash}"
                }
            }

            branches['PD'] = {
                pd_githash = sh(returnStdout: true, script: "cat ${binary}/pd_latest/src/.githash").trim()
                def pd_latest = ""
                if (fileExists("pd_githash.latest")) {
                    pd_latest = readFile "pd_githash.latest"
                }
                if (pd_githash != pd_latest) {
                    // prepare
                    sh """
                    rm -rf pd_build && mkdir pd_build
                    cp ${binary}/pd/${pd_githash}/bin/${platform}/* pd_build/
                    cp ${binary}/pd/${pd_githash}/conf/config.toml pd_build/

                    cat > pd_build/Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY pd-server /pd-server
COPY pd-ctl /pd-ctl
COPY config.toml /config.toml
EXPOSE 2379 2380
ENTRYPOINT ["/pd-server"]
__EOF__
                    """

                    // build
                    def pd_image = docker.build('pingcap/pd', "pd_build")

                    // push
                    retry(10) {
                        timeout(time: 10, unit: 'MINUTES') {
                            pd_image.push()
                        }
                    }

                    writeFile file: 'pd_githash.latest', text: "${pd_githash}"
                }
            }

            parallel branches
        }
        stage('Publish Binary') {
            def branches = [:]

            branches['linux-amd64'] = {
                def target_platform = 'linux-amd64'
                def target_package = "tidb-latest-${target_platform}"

                sh """
                rm -rf ${target_package} && mkdir -p ${target_package}/bin && mkdir -p ${target_package}/conf
                # TiKV
                cp ${binary}/tikv/${tikv_githash}/bin/${target_platform}/tikv-server ${target_package}/bin
                cp ${binary}/tikv/${tikv_githash}/conf/config-template.toml ${target_package}/conf/tikv.toml
                # TiDB
                cp ${binary}/tidb/${tidb_githash}/bin/${target_platform}/tidb-server ${target_package}/bin
                # PD
                cp ${binary}/pd/${pd_githash}/bin/${target_platform}/* ${target_package}/bin
                cp ${binary}/pd/${pd_githash}/conf/config.toml ${target_package}/conf/pd.toml
                # Package
                tar czvf ${target_package}.tar.gz ${target_package}
                sha256sum ${target_package}.tar.gz > ${target_package}.sha256
                md5sum ${target_package}.tar.gz > ${target_package}.md5
                # Upload
                /usr/bin/upload.py ${target_package}.tar.gz ${target_package}.tar.gz
                /usr/bin/upload.py ${target_package}.sha256 ${target_package}.sha256
                /usr/bin/upload.py ${target_package}.md5 ${target_package}.md5
                """
            }

            branches['linux-amd64-centos6'] = {
                def target_platform = 'linux-amd64-centos6'
                def target_package = "tidb-latest-${target_platform}"

                sh """
                rm -rf ${target_package} && mkdir -p ${target_package}/bin && mkdir -p ${target_package}/conf
                # TiKV
                cp ${binary}/tikv/${tikv_githash}/bin/${target_platform}/tikv-server ${target_package}/bin
                cp ${binary}/tikv/${tikv_githash}/conf/config-template.toml ${target_package}/conf/tikv.toml
                # TiDB
                cp ${binary}/tidb/${tidb_githash}/bin/${target_platform}/tidb-server ${target_package}/bin
                # PD
                cp ${binary}/pd/${pd_githash}/bin/${target_platform}/* ${target_package}/bin
                cp ${binary}/pd/${pd_githash}/conf/config.toml ${target_package}/conf/pd.toml
                # Package
                tar czvf ${target_package}.tar.gz ${target_package}
                sha256sum ${target_package}.tar.gz > ${target_package}.sha256
                md5sum ${target_package}.tar.gz > ${target_package}.md5
                # Upload
                /usr/bin/upload.py ${target_package}.tar.gz ${target_package}.tar.gz
                /usr/bin/upload.py ${target_package}.sha256 ${target_package}.sha256
                /usr/bin/upload.py ${target_package}.md5 ${target_package}.md5
                """
            }

            parallel branches
        }

        currentBuild.result = "SUCCESS"
    }

    def duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000

    def slackMsg = "" +
            "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}, " +
            "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
    } else {
        slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "${slackMsg}"
    }
}

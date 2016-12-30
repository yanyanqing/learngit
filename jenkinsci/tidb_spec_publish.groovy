#!groovy

node('master') {
    def workspace = pwd()
    def binary = "/binary_registry"
    def platform = "linux-amd64"
    def tag = "${version}"
    def tikv_githash = "${githash_tikv}"
    def tidb_githash = "${githash_tidb}"
    def pd_githash = "${githash_pd}"

    catchError {
        stage('Publish Docker') {
            def branches = [:]

            branches['TiDB'] = {
                def tidb_latest = ""
                if (fileExists("tidb_${tag}.latest")) {
                    tidb_latest = readFile "tidb_${tag}.latest"
                }
                if (tidb_githash != tidb_latest) {
                    // prepare
                    sh """
                    rm -rf tidb_${tag}_build && mkdir tidb_${tag}_build
                    cp ${binary}/tidb/${tidb_githash}/bin/${platform}/tidb-server tidb_${tag}_build/

                    cat > tidb_${tag}_build/Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY tidb-server /tidb-server
EXPOSE 4000
ENTRYPOINT ["/tidb-server"]
__EOF__
                    """

                    // build
                    def tidb_image = docker.build("pingcap/tidb:${tag}", "tidb_${tag}_build")

                    // push
                    retry(10) {
                        timeout(time: 10, unit: 'MINUTES') {
                            tidb_image.push()
                        }
                    }

                    writeFile file: "tidb_${tag}.latest", text: "${tidb_githash}"
                }
            }

            branches['TiKV'] = {
                def tikv_latest = ""
                if (fileExists("tikv_${tag}.latest")) {
                    tikv_latest = readFile "tikv_${tag}.latest"
                }
                if (tikv_githash != tikv_latest) {
                    // prepare
                    sh """
                    rm -rf tikv_${tag}_build && mkdir tikv_${tag}_build
                    cp ${binary}/tikv/${tikv_githash}/bin/${platform}/tikv-server tikv_${tag}_build/
                    cp ${binary}/tikv/${tikv_githash}/conf/config-template.toml tikv_${tag}_build/

                    cat > tikv_${tag}_build/Dockerfile << __EOF__
FROM pingcap/alpine-glibc
ENV TZ /etc/localtime
COPY tikv-server /tikv-server
COPY config-template.toml /config-template.toml
EXPOSE 20160
ENTRYPOINT ["/tikv-server"]
__EOF__
                    """

                    // build
                    def tikv_image = docker.build("pingcap/tikv:${tag}", "tikv_${tag}_build")

                    // push
                    retry(10) {
                        timeout(time: 10, unit: 'MINUTES') {
                            tikv_image.push()
                        }
                    }

                    writeFile file: "tikv_${tag}.latest", text: "${tikv_githash}"
                }
            }

            branches['PD'] = {
                def pd_latest = ""
                if (fileExists("pd_${tag}.latest")) {
                    pd_latest = readFile "pd_${tag}.latest"
                }
                if (pd_githash != pd_latest) {
                    // prepare
                    sh """
                    rm -rf pd_${tag}_build && mkdir pd_${tag}_build
                    cp ${binary}/pd/${pd_githash}/bin/${platform}/pd-server pd_${tag}_build/
                    cp ${binary}/pd/${pd_githash}/conf/config.toml pd_${tag}_build/

                    cat > pd_${tag}_build/Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY pd-server /pd-server
COPY config.toml /config.toml
EXPOSE 2379 2380
ENTRYPOINT ["/pd-server"]
__EOF__
                    """

                    // build
                    def pd_image = docker.build("pingcap/pd:${tag}", "pd_${tag}_build")

                    // push
                    retry(10) {
                        timeout(time: 10, unit: 'MINUTES') {
                            pd_image.push()
                        }
                    }

                    writeFile file: "pd_${tag}.latest", text: "${pd_githash}"
                }
            }

            parallel branches
        }
        stage('Publish Binary') {
            def branches = [:]

            branches['linux-amd64'] = {
                def target_platform = 'linux-amd64'
                def target_package = "tidb-${tag}-${target_platform}"

                sh """
                rm -rf ${target_package} && mkdir -p ${target_package}/bin && mkdir -p ${target_package}/conf
                # TiKV
                cp ${binary}/tikv/${tikv_githash}/bin/${target_platform}/tikv-server ${target_package}/bin
                cp ${binary}/tikv/${tikv_githash}/conf/config-template.toml ${target_package}/conf/tikv.toml
                # TiDB
                cp ${binary}/tidb/${tidb_githash}/bin/${target_platform}/tidb-server ${target_package}/bin
                # PD
                cp ${binary}/pd/${pd_githash}/bin/${target_platform}/pd-server ${target_package}/bin
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
                def target_package = "tidb-${tag}-${target_platform}"

                sh """
                rm -rf ${target_package} && mkdir -p ${target_package}/bin && mkdir -p ${target_package}/conf
                # TiKV
                cp ${binary}/tikv/${tikv_githash}/bin/${target_platform}/tikv-server ${target_package}/bin
                cp ${binary}/tikv/${tikv_githash}/conf/config-template.toml ${target_package}/conf/tikv.toml
                # TiDB
                cp ${binary}/tidb/${tidb_githash}/bin/${target_platform}/tidb-server ${target_package}/bin
                # PD
                cp ${binary}/pd/${pd_githash}/bin/${target_platform}/pd-server ${target_package}/bin
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


    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "" +
                "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${currentBuild.duration}, " +
                "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"
    } else {
        slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "" +
                "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${currentBuild.duration}, " +
                "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"
    }
}
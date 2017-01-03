#!groovy

node('material') {
    def workspace = pwd()
    env.GOPATH = "${workspace}/go:/go"
    env.GOROOT = "/usr/local/go"
    env.PATH = "${workspace}/go/bin:/go/bin:${env.GOROOT}/bin:/bin:${env.PATH}"
    def pingcap = "${workspace}/go/src/github.com/pingcap"
    def tidb_path = "${pingcap}/tidb"
    def binlog_path = "${pingcap}/tidb-binlog"
    def platform = "linux-amd64"
    def binary = "/binary_registry"
    def githash_binlog

    catchError {
        stage('SCM Checkout') {
            // tidb-binlog
            dir("${binlog_path}") {
                git credentialsId: 'github-liuyin', url: 'git@github.com:pingcap/tidb-binlog.git'
                githash_binlog = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }

            // tidb
            dir("${tidb_path}") {
                git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'git@github.com:pingcap/tidb.git'
            }

            // goleveldb
            dir("go/src/github.com/pingcap/goleveldb") {
                git changelog: false, credentialsId: 'github-liuyin', poll: false, url: 'https://github.com/pingcap/goleveldb.git'
            }

            withEnv(["http_proxy=${proxy}", "https_proxy=${proxy}"]) {
                retry(3) {
                    sh """
                    go get -d -u github.com/BurntSushi/toml
                    go get -d -u github.com/go-sql-driver/mysql
                    go get -d -u github.com/juju/errors
                    go get -d -u github.com/ngaut/log
                    go get -d -u github.com/golang/snappy
                    go get -d -u github.com/petar/GoLLRB/llrb
                    go get -d -u golang.org/x/text
                    """
                }
            }
        }

        stage('Build') {
            sh """
            cd ${tidb_path} && make
            cd ${binlog_path} && make
            cd ${binlog_path}/test && go build
            """
        }

        stage('Stash') {
            sh """
            rm -rf release

            # tidb-binlog
            mkdir -p release/binlog/bin/${platform} release/binlog/conf release/binlog/src
            cp ${binlog_path}/bin/* release/binlog/bin/${platform}/
            cp ${binlog_path}/cmd/drainer/drainer.toml release/binlog/conf/drainer.toml
            cp ${binlog_path}/cmd/cistern/cistern.toml release/binlog/conf/cistern.toml
            cp ${binlog_path}/cmd/pump/pump.toml release/binlog/conf/pump.toml
            echo '${githash_binlog}' > release/binlog/src/.githash

            # tidb
            mkdir -p release/tidb
            cp -R ${binary}/tidb_latest/* release/tidb/
            
            # tikv
            mkdir -p release/tikv
            cp -R ${binary}/tikv_latest/* release/tikv/
            
            # pd
            mkdir -p release/pd
            cp -R ${binary}/pd_latest/* release/pd/
            """

            stash includes: 'go/src/github.com/pingcap/**', name: 'source-pingcap'
            stash includes: "release/tidb/bin/${platform}/**", name: "release-tidb-${platform}"
            stash includes: "release/tikv/bin/${platform}/**", name: "release-tikv-${platform}"
            stash includes: "release/pd/bin/${platform}/**", name: "release-pd-${platform}"
            stash includes: "release/binlog/**", name: "release-binlog-${platform}"
        }

        stage('Test') {
            node('worker') {
                deleteDir()
                unstash 'source-pingcap'
                sh "cd ${binlog_path} && make test"
            }
        }

        stage('Integration Test') {
            def branches = [:]

            branches["Mixed DDL & DML Test"] = {
                node('worker') {
                    deleteDir()
                    unstash("source-pingcap")
                    unstash("release-tidb-${platform}")
                    unstash("release-tikv-${platform}")
                    unstash("release-pd-${platform}")
                    unstash("release-binlog-${platform}")

                    try {
                        sh """
                        # pd-server
                        killall -9 pd-server || true
                        release/pd/bin/${platform}/pd-server --name=pd --data-dir=pd &>pd_test.log &
                        sleep 5
                        
                        # tikv-server
                        killall -9 tikv-server || true
                        release/tikv/bin/${platform}/tikv-server --pd=127.0.0.1:2379 -s kvdata --addr=127.0.0.1:20160 &>tikv_test.log &
                        sleep 5
                        
                        # pump
                        killall -9 pump || true
                        rm -rf /tmp/pump.sock
                        release/binlog/bin/${platform}/pump -addr=127.0.0.1:8250 -socket=/tmp/pump.sock &>pump_test.log &
                        sleep 5

                        # tidb-server
                        killall -9 tidb-server || true
                        release/tidb/bin/${platform}/tidb-server -store=tikv -path='127.0.0.1:2379' -binlog-socket=/tmp/pump.sock &>source_tidb.log &
                        sleep 5
                        release/tidb/bin/${platform}/tidb-server -P 3306 -status=20080 &>target_tidb.log &
                        sleep 5

                        # cistern
                        killall -9 cistern || true
                        release/binlog/bin/${platform}/cistern &>cistern_test.log &
                        sleep 5
 
                        # drainer
                        killall -9 drainer || true
                        release/binlog/bin/${platform}/drainer -config=release/binlog/conf/drainer.toml &>drainer_test.log &
                        sleep 5
                        """

                        sh "cd ${binlog_path}/test && ./test -config=config.toml"

                    } catch (err) {
                        throw err
                    } finally {
                        sh "killall -9 drainer || true"
                        sh "killall -9 tidb-server || true"
                        sh "killall -9 pump || true"
                        sh "killall -9 cistern || true"
                        sh "killall -9 tikv-server || true"
                        sh "killall -9 pd-server || true"
                    }
                }
            }

            parallel branches
        }

        stage('Save Binary') {
            def target = "${binary}/binlog/${githash_binlog}"
            sh """
            rm -rf ${target} && mkdir -p ${target}
            cp -R release/binlog/* ${target}/
            ln -sfT ${target} ${binary}/binlog_latest
            """
        }

        stage('Publish Binary') {
            node('master') {
                def branches = [:]

                branches['linux-amd64'] = {
                    def target_platform = 'linux-amd64'
                    def target_package = "tidb-binlog-latest-${target_platform}"

                    sh """
                    rm -rf ${target_package} && mkdir -p ${target_package}/bin && mkdir -p ${target_package}/conf
                    # TiDB-Binlog
                    cp ${binary}/binlog/${githash_binlog}/bin/${target_platform}/* ${target_package}/bin/
                    cp ${binary}/binlog/${githash_binlog}/conf/* ${target_package}/conf/
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
        }

        currentBuild.result = "SUCCESS"
    }

    def changeLogSets = currentBuild.changeSets
    def changeLogText = ""
    for (int i = 0; i < changeLogSets.size(); i++) {
        def entries = changeLogSets[i].items
        for (int j = 0; j < entries.length; j++) {
            def entry = entries[j]
            changeLogText += "\n${entry.commitId} by ${entry.author} on ${new Date(entry.timestamp)}: ${entry.msg}"
        }
    }

    def duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#tidb-tools', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "" +
                "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}, " +
                "${changeLogText}" + "\n"
                "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"
    } else {
        slackSend channel: '#tidb-tools', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-token', message: "" +
                "${env.JOB_NAME}-${env.BUILD_NUMBER}: ${currentBuild.result}, Duration: ${duration}, " +
                "${changeLogText}" + "\n"
                "${env.JENKINS_URL}blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline"
    }
}
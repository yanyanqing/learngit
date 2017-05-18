def call(HOSTIP, RELEASE_TAG) {

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

return this

def call(HOSTIP, RELEASE_TAG) {

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

return this

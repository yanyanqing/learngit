#! groovy

node('master') {
    catchError {
        stage('Pruning') {
            sh '''
            for CONTAINER in `docker ps -f status=exited -q`; do
                docker rm ${CONTAINER}
            done
            for IMG in `docker images -f dangling=true -q`; do
                docker rmi ${IMG}
            done
            '''
        }

        currentBuild.result = "SUCCESS"
    }
}
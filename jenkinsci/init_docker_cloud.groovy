#!groovy

node('master') {
    stage('Init Slaves') {
        def branches = [:]
        for (int i = 0; i < 23; i++) {
            branches["branch-worker-${i}"] = {
                node('worker') {
                    sleep 300
                    echo "$i: ${env.NODE_NAME}: init ok!"
                }
            }
        }
        for (int i = 0; i < 7; i++) {
            branches["branch-hiworker-${i}"] = {
                node('worker-high') {
                    sleep 300
                    echo "$i: ${env.NODE_NAME}: init ok!"
                }
            }
        }
        parallel branches
    }
}
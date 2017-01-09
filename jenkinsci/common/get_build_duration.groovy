def call() {
    def duration = (System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000
    return duration
}

return this

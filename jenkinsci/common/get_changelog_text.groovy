def call() {
    def changeLogText = ""
    for (int i = 0; i < currentBuild.changeSets.size(); i++) {
        for (int j = 0; j < currentBuild.changeSets[i].items.length; j++) {
            def commitId = "${currentBuild.changeSets[i].items[j].commitId}"
            def commitMsg = "${currentBuild.changeSets[i].items[j].msg}"
            changeLogText += "\n" + commitId.take(7) + " - " + commitMsg
        }
    }

    return changeLogText
}

return this

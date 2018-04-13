package main

import (
	"flag"
	"fmt"
	"net/http"
	"os"

	"github.com/nlopes/slack"
	log "github.com/sirupsen/logrus"
)

var (
	helpFlag              = flag.Bool("help", false, "show detailed help message")
	portFlag              = flag.String("port", "6666", "server port to be listened")
	botTokenFlag          = flag.String("bot-token", "", "bot user token to access to slack API")
	botIDFlag             = flag.String("bot", "", "bot user ID")
	verificationTokenFlag = flag.String("verify-token", "", "used to validate interactive message from slack")
	channelIDFlag         = flag.String("channel", "", "slack channel ID where bot is working")
)

const usage = `slack-release-bot: the backend server of a slack app used for the tidb versioning.

Usage: slack-release-bot <args...>

-help              show detailed help message.
-port              server port to be listened.
-bot-token         bot user token to access to slack API.
-bot               bot user ID
-verify-token      used to validate interactive message from slack.
-channel           slack channel ID where bot is working.
`

func init() {
	// Log as Text instead of the default ASCII formatter
	log.SetFormatter(&log.TextFormatter{})

	// Output to stdout instead of the default stderr
	log.SetOutput(os.Stdout)

	// Only log the warning severity or above
	log.SetLevel(log.DebugLevel)
}

func main() {
	os.Exit(realMain())
}

func realMain() int {
	flag.Parse()

	if *helpFlag {
		fmt.Fprint(os.Stderr, usage)
		return 2
	}

	if *botTokenFlag == "" {
		log.Error("bot user token should be given")
		return 1
	}

	if *verificationTokenFlag == "" {
		log.Error(os.Stderr, "verification token for interactive message must be specified")
		return 1
	}

	// Listening slack event and response
	log.Infof("Start slack realtime event listening")
	client := slack.New(*botTokenFlag)
	slackListener := &SlackListener{
		client:    client,
		botID:     *botIDFlag,
		channelID: *channelIDFlag,
	}
	go slackListener.ListenAndResponse()

	// Register handler to receive interactive message
	// responses from slack (kicked by user action)
	http.Handle("/interaction", interactionHandler{
		verificationToken: *verificationTokenFlag,
	})

	log.Infof("Server listening on port[%s]", *portFlag)
	if err := http.ListenAndServe(":"+*portFlag, nil); err != nil {
		log.Errorf("%s", err)
		return 1
	}

	return 0
}

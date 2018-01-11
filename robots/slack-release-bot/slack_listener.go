package main

import (
	"fmt"
	"strings"

	"github.com/nlopes/slack"
	log "github.com/sirupsen/logrus"
)

const (
	// action is used for slack attament action
	actionSelect = "select"
	actionStart  = "start"
	actionCancel = "cancel"
)

type SlackListener struct {
	client    *slack.Client
	botID     string
	channelID string
}

// LstenAndResponse listens slack events and response
// particular messages. It replies by slack message button
func (s *SlackListener) ListenAndResponse() {
	rtm := s.client.NewRTM()

	// start listening slack events
	go rtm.ManageConnection()

	// Handle slack events
	for msg := range rtm.IncomingEvents {
		switch ev := msg.Data.(type) {
		case *slack.MessageEvent:
			if err := s.handleMessageEvent(ev); err != nil {
				log.Error("Failed to handle message: %s", err)
			}
		}
	}
}

// handleMessageEvent processes message events
func (s *SlackListener) handleMessageEvent(ev *slack.MessageEvent) error {
	// Only response in specific channel, ignore else
	if ev.Channel != s.channelID {
		log.Debugf("Ignore channel[%s] message: %s", ev.Channel, ev.Msg.Text)
		return nil
	}

	if !strings.HasPrefix(ev.Msg.Text, fmt.Sprintf("<@%s> ", s.botID)) {
		log.Debugf("Ignore msg: %s", ev.Msg.Text)
		return nil
	}

	// Parse message
	m := strings.Split(strings.TrimSpace(ev.Msg.Text), " ")[1:]
	if len(m) == 0 || m[0] != "hi" {
		log.Debugf("Ignore msg: %s", ev.Msg.Text)
		return nil
	}

	// value is passed to message handler when request is approved
	attachment := slack.Attachment{
		Text:       "Please specify the branch version of TiDB component to release: ",
		Color:      "#f9a41b",
		CallbackID: "release",
		Actions: []slack.AttachmentAction{
			{
				Name: actionSelect,
				Type: "select",
				Options: []slack.AttachmentActionOption{
					{
						Text:  "release-1.0",
						Value: "release-1.0",
					},
					{
						Text:  "release-1.2",
						Value: "release-1.2",
					},
				},
			},
			{
				Name:  actionCancel,
				Text:  "Cancel",
				Type:  "button",
				Style: "danger",
			},
		},
	}

	params := slack.PostMessageParameters{
		Attachments: []slack.Attachment{
			attachment,
		},
	}

	if _, _, err := s.client.PostMessage(ev.Channel, "", params); err != nil {
		return fmt.Errorf("Failed to post message: %s", err)
	}

	return nil
}

package patrol

import (
	"fmt"
	"strings"
	"time"

	"github.com/juju/errors"
	"github.com/ngaut/log"
	"github.com/nlopes/slack"
)

var (
	maxWaitTime = 30 * time.Second
)

// SlackSender holds slack configueration
type SlackSender struct {
	cfg *Config

	waitTime time.Time
	client   *slack.Client
}

// newSlackSender returns an instance of SlackSender
func newSlackSender(cfg *Config) *SlackSender {
	client := slack.New(cfg.BotToken)
	return &SlackSender{
		cfg:    cfg,
		client: client,
	}
}

func (sender *SlackSender) sendMsg(postMsg *Post) error {
	postStr := fmt.Sprintf("[New article comes from:[%s]]", postMsg.URL)
	msgOpt := slack.MsgOptionText(postStr, false)
	_, _, _, err := sender.client.SendMessage(sender.cfg.ChannelID, msgOpt)
	if err != nil {
		log.Errorf("slack client send message error %s", err)
		return errors.Trace(err)
	}

	author := strings.Replace(postMsg.Author, " ", "-", -1)
	attachment := slack.Attachment{
		Color:      "#f9a41b",
		AuthorName: postMsg.Author,
		AuthorLink: "https://www.quora.com/profile/" + author,
		Title:      postMsg.Title,
		TitleLink:  postMsg.Link,
		Text:       postMsg.Snippet,
		ID:         postMsg.ArticlePos,
		Actions: []slack.AttachmentAction{
			{
				Options: []slack.AttachmentActionOption{
					{
						Text:  "new post",
						Value: "newpost",
					},
				},
			},
		},
	}
	params := slack.PostMessageParameters{
		Attachments: []slack.Attachment{
			attachment,
		},
	}
	if _, _, err := sender.client.PostMessage(sender.cfg.ChannelID, "", params); err != nil {
		log.Errorf("Failed to post message: %s", err)
		return errors.Trace(err)
	}

	return nil
}

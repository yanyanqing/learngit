package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/google/go-github/github"
	"golang.org/x/oauth2"
)

const (
	token            = "c2010a062a3d25ac93cfab8ff8c6838ea5bc03e2"
	allowedAliveTime = time.Hour * 24
	timeFormat       = "2006-01-02 15:04:05"
	slackUrl         = "https://hooks.slack.com/services/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"
)

func exitWithErr(err error) {
	if err == nil {
		return
	}

	println(err.Error())
	os.Exit(1)
}

func pullIgnored(title string) bool {
	title = strings.ToUpper(title)
	return strings.Contains(title, "DNM") || strings.Contains(title, "WIP")
}

func alert(channel string, msg string) {
	payload := map[string]string{
		"channel":    channel,
		"username":   "sre-bot",
		"text":       msg,
		"icon_emoji": ":ghost:",
	}

	data, err := json.Marshal(payload)
	exitWithErr(err)
	data = []byte(fmt.Sprintf("payload=%s", string(data)))

	_, err = http.Post(slackUrl, "application/x-www-form-urlencoded", bytes.NewBuffer(data))
	exitWithErr(err)
}

func check(client *github.Client, channel string, repos []string) {
	ctx := context.Background()

	var buf bytes.Buffer

	buf.WriteString("重要的事情说三遍\n")
	for i := 0; i < 3; i++ {
		buf.WriteString(fmt.Sprintf("PR 超过一天鸟。。。\n"))
	}

	hit := false

	for _, repo := range repos {
		pulls, _, err := client.PullRequests.List(ctx, "pingcap", repo, nil)
		exitWithErr(err)

		now := time.Now()
		for _, pull := range pulls {
			createTime := pull.GetCreatedAt()
			title := pull.GetTitle()

			if pullIgnored(title) {
				continue
			}

			if now.Sub(createTime) > allowedAliveTime {
				hit = true
				fmt.Fprintf(&buf, "%s %s @%s %s %s\n", repo, createTime.Format(timeFormat), pull.User.GetLogin(), pull.GetHTMLURL(), title)
			}
		}
	}

	if hit {
		alert(channel, buf.String())
	}
}

func main() {
	ctx := context.Background()
	ts := oauth2.StaticTokenSource(
		&oauth2.Token{AccessToken: token},
	)
	tc := oauth2.NewClient(ctx, ts)

	client := github.NewClient(tc)

	tbl := []struct {
		Channel string
		Repos   []string
	}{
		{"kv", []string{"tikv", "rust-rocksdb", "grpc-rs"}},
		{"pd", []string{"pd"}},
		{"tidb", []string{"tidb"}},
	}

	for _, t := range tbl {
		check(client, t.Channel, t.Repos)
	}
}

package main

import (
	"encoding/json"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/andygrunwald/go-jira"
	"github.com/google/go-github/github"
	"github.com/juju/errors"
	"golang.org/x/net/context"

	"k8s.io/test-infra/prow/hook"
	"k8s.io/test-infra/prow/plugins"
)

// syncInterval represents full synchronization interval
var syncInterval = 1 * time.Minute

type Server struct {
	cfg *Config
	mux sync.Mutex

	ctx    context.Context
	cancel context.CancelFunc

	gHook   *hook.Server
	gClient *GHClient
	jClient *JIRAClient
}

func NewServer(cfg *Config) *Server {
	ctx, cancel := context.WithCancel(context.Background())
	return &Server{
		cfg:    cfg,
		ctx:    ctx,
		cancel: cancel,
	}
}

func (s *Server) init() error {
	var err error
	s.jClient, err = newJIRAClient(s.cfg)
	if err != nil {
		return errors.Trace(err)
	}

	s.gClient = NewGHClient(s.cfg)

	pa := &plugins.PluginAgent{}
	pa.Set(&plugins.Configuration{
		ExternalPlugins: map[string][]plugins.ExternalPlugin{
			s.cfg.JConfig.Project: {
				{
					Name:     s.cfg.JConfig.Project,
					Endpoint: s.cfg.JConfig.Endpoint,
					Events:   []string{"issues", "issue_comment"},
				},
			},
		},
	})

	s.gHook = &hook.Server{
		Plugins: pa,
	}

	return nil
}

// ServeHTTP validates an incoming webhook and puts it into the event channel.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	log := s.cfg.getLogger()
	eventType, _, payload, ok := hook.ValidateWebhook(w, r, ([]byte)(s.cfg.GConfig.WebhookSecret))
	if !ok {
		log.Errorf("validate webhook error ")
		return
	}

	go s.demuxExternal(eventType, s.gHook.Plugins.Config().ExternalPlugins[s.cfg.JConfig.Project], payload)
}

// demuxExternal handles the provided payload to the external plugins.
func (s *Server) demuxExternal(eventType string, externalPlugins []plugins.ExternalPlugin, payload []byte) {
	log := s.cfg.getLogger()
	for _, p := range externalPlugins {
		go func(p plugins.ExternalPlugin) {
			if err := s.syncIssues(eventType, payload); err != nil {
				log.WithError(err).WithField("external-plugin", p.Name).Error("Error sync issue to external plugin.")
			} else {
				log.WithField("external-plugin", p.Name).Info("sync issue to external plugin")
			}
		}(p)
	}
}

func (s *Server) syncIssues(eventType string, payload []byte) error {
	log := s.cfg.getLogger()

	switch eventType {
	case "issues":
		var i github.IssueEvent
		if err := json.Unmarshal(payload, &i); err != nil {
			return errors.Trace(err)
		}
		return errors.Trace(s.handleIssues(i))
	case "issue_comment":
		var ic github.IssueCommentEvent
		if err := json.Unmarshal(payload, &ic); err != nil {
			return errors.Trace(err)
		}
		return errors.Trace(s.handleIssueComment(ic))
	default:
		log.Errorf("Unsupported type %v.", eventType)
		return errors.New("Unsupported type")
	}
}

func (s *Server) handleIssues(gIssue github.IssueEvent) error {
	log := s.cfg.getLogger()
	s.mux.Lock()
	defer s.mux.Unlock()
	gid := gIssue.Issue.GetID()

	jIssues, err := s.jClient.ListIssues([]int{int(gid)})
	if err != nil {
		log.Errorf("jClient listIssues error %v", err)
		return errors.Trace(err)
	}

	if len(jIssues) == 0 {
		log.Infof("Creating JIRA issue based on GitHub issue #%d", *gIssue.Issue.Number)
		if err = CreateIssue(s.cfg, gIssue.Issue, s.gClient, s.jClient); err != nil {
			log.Errorf("Error creating issue Error: %v", err)
		}
	} else {
		log.Infof("Updating JIRA issue based on GitHub issue #%d", *gIssue.Issue.Number)
		if err = UpdateIssue(s.cfg, gIssue.Issue, &jIssues[0], s.gClient, s.jClient); err != nil {
			log.Errorf("Error updating issue error: %v", err)
		}
	}

	return errors.Trace(err)
}

func (s *Server) handleIssueComment(gComment github.IssueCommentEvent) error {
	log := s.cfg.getLogger()
	jIssues, err := s.jClient.ListIssues([]int{int(gComment.Issue.GetID())})
	var jComments []jira.Comment
	jIssue, _, err := s.jClient.client.Issue.Get(jIssues[0].ID, nil)
	if err != nil {
		log.Errorf("while meeting issue comments, list isssue error %v", err)
		return errors.Trace(err)
	}

	if len(jIssues) > 0 && jIssue.Fields.Comments != nil {
		commentPtrs := jIssue.Fields.Comments.Comments
		jComments = make([]jira.Comment, len(commentPtrs))
		for i, v := range commentPtrs {
			jComments[i] = *v
		}
		log.Infof("JIRA issue %s has %d comments", jIssue.Key, len(jComments))
	}
	var found bool
	for _, jComment := range jComments {
		if !jCommentIDRegex.MatchString(jComment.Body) {
			continue
		}
		// matches[0] is the whole string, matches[1] is the ID
		matches := jCommentIDRegex.FindStringSubmatch(jComment.Body)
		id, _ := strconv.Atoi(matches[1])
		if *gComment.Comment.ID != int64(id) {
			continue
		}
		found = true

		UpdateComment(s.cfg, gComment.Comment, &jComment, jIssue, s.gClient, s.jClient)
		break
	}

	if !found {
		_, err := s.jClient.CreateComment(jIssue, gComment.Comment, s.gClient)
		if err != nil {
			return errors.Trace(err)
		}
	}

	return nil
}

func (s *Server) intialSync() error {
	log := s.cfg.getLogger()
	for {
		select {
		case <-s.ctx.Done():
			log.Infof("intialSync Done!")
			return nil
		case <-time.After(syncInterval):
			err := CompareIssues(s.cfg, s.gClient, s.jClient)
			if err != nil {
				return errors.Trace(err)
			}
		}
	}
}

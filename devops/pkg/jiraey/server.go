package main

import (
	"encoding/json"
	"io/ioutil"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/andygrunwald/go-jira"
	"github.com/google/go-github/github"
	"github.com/juju/errors"
	"github.com/sirupsen/logrus"
	"golang.org/x/net/context"

	"k8s.io/test-infra/prow/hook"
	"k8s.io/test-infra/prow/plugins"
	//	prow "k8s.io/test-infra/prow/github"
)

// syncInterval represents full synchronization interval
var syncInterval = 12 * time.Hour

//var syncInterval = 10 * time.Second

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

	extern := make(map[string][]plugins.ExternalPlugin)

	extPlugin := make([]plugins.ExternalPlugin, len(s.cfg.Rules))
	for i := 0; i < len(s.cfg.Rules); i++ {
		extPlugin[i] = plugins.ExternalPlugin{
			Name:     s.cfg.Rules[i].Project,
			Endpoint: s.cfg.JConfig.Endpoint,
			Events:   []string{"issues", "issue_comment"},
		}
	}

	extern["externPulgin"] = extPlugin

	pa.Set(&plugins.Configuration{
		ExternalPlugins: extern,
	})

	s.gHook = &hook.Server{
		Plugins: pa,
	}

	return nil
}

// ServeHTTP validates an incoming webhook and puts it into the event channel.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	log := s.cfg.GetLogger()
	eventType, _, payload, ok := ValidateWebhook(w, r)
	if !ok {
		log.Errorf("validate webhook error ")
		return
	}

	go s.demuxExternal(eventType, s.gHook.Plugins.Config().ExternalPlugins["externPulgin"], payload)
}

func ValidateWebhook(w http.ResponseWriter, r *http.Request) (string, string, []byte, bool) {
	defer r.Body.Close()

	// Our health check uses GET, so just kick back a 200.
	if r.Method == http.MethodGet {
		return "", "", nil, false
	}

	// Header checks: It must be a POST with an event type and a signature.
	if r.Method != http.MethodPost {
		resp := "405 Method not allowed"
		logrus.Debug(resp)
		http.Error(w, resp, http.StatusMethodNotAllowed)
		return "", "", nil, false
	}
	eventType := r.Header.Get("X-GitHub-Event")
	if eventType == "" {
		resp := "400 Bad Request: Missing X-GitHub-Event Header"
		logrus.Debug(resp)
		http.Error(w, resp, http.StatusBadRequest)
		return "", "", nil, false
	}
	eventGUID := r.Header.Get("X-GitHub-Delivery")
	if eventGUID == "" {
		resp := "400 Bad Request: Missing X-GitHub-Delivery Header"
		logrus.Debug(resp)
		http.Error(w, resp, http.StatusBadRequest)
		return "", "", nil, false
	}
	/*	sig := r.Header.Get("X-Hub-Signature")
		if sig == "" {
			resp := "403 Forbidden: Missing X-Hub-Signature"
			logrus.Debug(resp)
			http.Error(w, resp, http.StatusForbidden)
			return "", "", nil, false
		}*/
	contentType := r.Header.Get("content-type")
	if contentType != "application/json" {
		resp := "400 Bad Request: Hook only accepts content-type: application/json - please reconfigure this hook on GitHub"
		logrus.Debug(resp)
		http.Error(w, resp, http.StatusBadRequest)
		return "", "", nil, false
	}

	payload, err := ioutil.ReadAll(r.Body)
	if err != nil {
		resp := "500 Internal Server Error: Failed to read request body"
		logrus.Debug(resp)
		http.Error(w, resp, http.StatusInternalServerError)
		return "", "", nil, false
	}

	return eventType, eventGUID, payload, true
}

// demuxExternal handles the provided payload to the external plugins.
func (s *Server) demuxExternal(eventType string, externalPlugins []plugins.ExternalPlugin, payload []byte) {
	log := s.cfg.GetLogger()
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
	log := s.cfg.GetLogger()

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
	case "push":
		return nil
	default:
		log.Errorf("Unsupported type %v.", eventType)
		return errors.New("Unsupported type")
	}
}

func (s *Server) handleIssues(gIssue github.IssueEvent) error {
	log := s.cfg.GetLogger()
	s.mux.Lock()
	defer s.mux.Unlock()
	gid := gIssue.Issue.GetID()

	log.Infof("srcRepo %v", *gIssue.Issue)
	//repo := *gIssue.Issue.Repository.FullName
	//log.Infof("srcRepo %v", repo)
	repoURL := strings.Split(*gIssue.Issue.RepositoryURL, "/")
	repo, user := repoURL[len(repoURL)-1], repoURL[len(repoURL)-2]
	repoMap := s.cfg.GetRepoMap()
	jIssues, err := s.jClient.ListIssues(repoMap[user+"/"+repo].Project, []int{int(gid)})
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
	log := s.cfg.GetLogger()

	//	repo := *gComment.Repo.FullName
	repoURL := strings.Split(*gComment.Issue.RepositoryURL, "/")
	repo, user := repoURL[len(repoURL)-1], repoURL[len(repoURL)-2]
	repoMap := s.cfg.GetRepoMap()

	jIssues, err := s.jClient.ListIssues(repoMap[user+"/"+repo].Project, []int{int(gComment.Issue.GetID())})

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
	log := s.cfg.GetLogger()
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

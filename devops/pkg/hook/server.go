package main

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	//	"github.com/andygrunwald/go-jira"
	"github.com/google/go-github/github"
	"github.com/juju/errors"
	"github.com/ngaut/log"
	"github.com/sirupsen/logrus"

	prowGH "k8s.io/test-infra/prow/github"
	"k8s.io/test-infra/prow/hook"
	"k8s.io/test-infra/prow/plugins"
)

//var idMap = make(map[int]string)

type Server struct {
	cfg     *Config
	gHook   *hook.Server
	gClient *GHClient
	jClient *JIRAClient
}

func NewServer(cfg *Config) *Server {
	return &Server{cfg: cfg}
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
	eventType, eventGUID, payload, ok := hook.ValidateWebhook(w, r, ([]byte)(s.cfg.WebhookSecret))
	if !ok {
		log.Errorf("validate webhook error ")
		return
	}

	if err := s.demuxEvent(eventType, eventGUID, payload, r.Header); err != nil {
		log.Errorf("Error parsing event.")
	}
}

func (s *Server) demuxEvent(eventType, eventGUID string, payload []byte, h http.Header) error {
	l := logrus.WithFields(
		logrus.Fields{
			"event-type":     eventType,
			prowGH.EventGUID: eventGUID,
		},
	)
	// We don't want to fail the hook due to a metrics error.
	// if counter, err := s.gHook.Metrics.WebhookCounter.GetMetricWithLabelValues(eventType); err != nil {
	// 	l.WithError(err).Warn("Failed to get metric for eventType " + eventType)
	// } else {
	// 	counter.Inc()
	// }
	var srcRepo string
	switch eventType {
	case "issues":
		var i prowGH.IssueEvent
		if err := json.Unmarshal(payload, &i); err != nil {
			return err
		}
		i.GUID = eventGUID
		srcRepo = i.Repo.FullName
		//go s.gHook.handleIssueEvent(l, i)
	case "issue_comment":
		var ic prowGH.IssueCommentEvent
		if err := json.Unmarshal(payload, &ic); err != nil {
			return err
		}
		ic.GUID = eventGUID
		srcRepo = ic.Repo.FullName
		//go s.gHook.handleIssueCommentEvent(l, ic)
	}
	// Demux events only to external plugins that require this event.
	//	if external := s.needDemux(eventType, srcRepo); len(external) > 0 {
	//		log.Infof("external")
	//		go s.demuxExternal(eventType, l, external, payload)
	//	}
	go s.demuxExternal(eventType, l, s.gHook.Plugins.Config().ExternalPlugins[s.cfg.JConfig.Project], payload)
	log.Infof("srcRepo %v", srcRepo)
	return nil
}

// needDemux returns whether there are any external plugins that need to
// get the present event.
func (s *Server) needDemux(eventType, srcRepo string) []plugins.ExternalPlugin {
	var matching []plugins.ExternalPlugin
	srcOrg := strings.Split(srcRepo, "/")[0]

	for repo, plugins := range s.gHook.Plugins.Config().ExternalPlugins {
		// Make sure the repositories match
		var matchesRepo bool
		if repo == srcRepo {
			matchesRepo = true
		}
		// If repo is an org, we need to compare orgs.
		if !matchesRepo && !strings.Contains(repo, "/") && repo == srcOrg {
			matchesRepo = true
		}
		// No need to continue if the repos don't match.
		if !matchesRepo {
			continue
		}

		// Make sure the events match
		for _, p := range plugins {
			if len(p.Events) == 0 {
				matching = append(matching, p)
			} else {
				for _, et := range p.Events {
					if et != eventType {
						continue
					}
					matching = append(matching, p)
					break
				}
			}
		}
	}

	return matching
}

// demuxExternal handles the provided payload to the external plugins.
func (s *Server) demuxExternal(eventType string, l *logrus.Entry, externalPlugins []plugins.ExternalPlugin, payload []byte) {
	for _, p := range externalPlugins {
		go func(p plugins.ExternalPlugin) {
			if err := s.syncIssues(eventType, l, payload); err != nil {
				l.WithError(err).WithField("external-plugin", p.Name).Error("Error sync issue to external plugin.")
			} else {
				l.WithField("external-plugin", p.Name).Info("sync issue to external plugin")
			}
		}(p)
	}
}

func (s *Server) syncIssues(eventType string, l *logrus.Entry, payload []byte) error {
	switch eventType {
	case "issues":
		var i github.IssueEvent
		if err := json.Unmarshal(payload, &i); err != nil {
			return errors.Trace(err)
		}
		go s.handleIssues(l, i)
	case "issue_comment":
		var ic github.IssueCommentEvent
		if err := json.Unmarshal(payload, &ic); err != nil {
			return errors.Trace(err)
		}
		go s.handleIssueComment(l, ic)
	default:
		l.Errorf("Unsupported type %v.", eventType)
		return errors.New("Unsupported type")
	}
	return nil
}

func (s *Server) handleIssues(l *logrus.Entry, gIssue github.IssueEvent) error {
	gid := gIssue.Issue.GetID()
	var found bool
	// var err error
	// var jIssues []jira.Issue
	// if idMap[gIssue.Issue.GetNumber()] != "" {
	// 	found = true
	// }
	// log.Infof("idMap %v", idMap)

	jIssues, err := s.jClient.ListIssues([]int{int(gid)})
	if err != nil {
		l.Errorf("jClient listIssues error %v", err)
		return errors.Trace(err)
	}
	if len(jIssues) > 0 {
		found = true
	}

	if !found {
		time.Sleep(5 * time.Second)
		jIssues, err = s.jClient.ListIssues([]int{int(gid)})
		if err != nil {
			l.Errorf("jClient listIssues error %v", err)
			return errors.Trace(err)
		}
		// if len(jIssues) == 0 && idMap[gIssue.Issue.GetNumber()] == "" {
		// 	found = false
		// } else {
		// 	found = true
		// }
		if len(jIssues) > 0 {
			found = true
		}
	}

	if !found {
		if err = CreateIssue(s.cfg, gIssue.Issue, s.gClient, s.jClient); err != nil {
			log.Errorf("Error creating issue Error: %v", err)
		}
	} else if len(jIssues) > 0 {
		if err = UpdateIssue(s.cfg, gIssue.Issue, &jIssues[0], s.gClient, s.jClient); err != nil {
			log.Errorf("Error updating issue error: %v", err)
		}
	}

	return errors.Trace(err)
}

func (s *Server) handleIssueComment(l *logrus.Entry, gComment github.IssueCommentEvent) error {
	jIssues, err := s.jClient.ListIssues([]int{int(gComment.Issue.GetID())})
	//	var jComments []jira.Comment
	jIssue, _, err := s.jClient.client.Issue.Get(jIssues[0].ID, nil)
	if err != nil {
		log.Errorf("while meeting issue comments, list isssue error %v", err)
		return errors.Trace(err)
	}

	err = CompareComments(s.cfg, gComment.Issue, jIssue, s.gClient, s.jClient)
	if err != nil {
		log.Errorf("while meeting issue comments, compare issue error %v", err)
		return errors.Trace(err)
	}

	return nil
}

// if len(jIssues) > 0 && jIssue.Fields.Comments != nil {
// 	commentPtrs := jIssue.Fields.Comments.Comments
// 	jComments = make([]jira.Comment, len(commentPtrs))
// 	for i, v := range commentPtrs {
// 		jComments[i] = *v
// 	}
// 	log.Debugf("JIRA issue %s has %d comments", jIssue.Key, len(jComments))
// }
// var found bool
// for _, jComment := range jComments {
// 	if !jCommentIDRegex.MatchString(jComment.Body) {
// 		continue
// 	}
// 	log.Infof("update issuecomment %v", jIssues)
// 	// matches[0] is the whole string, matches[1] is the ID
// 	matches := jCommentIDRegex.FindStringSubmatch(jComment.Body)
// 	id, _ := strconv.Atoi(matches[1])
// 	if *gComment.Comment.ID != int64(id) {
// 		continue
// 	}
// 	found = true

// 	s.updateIssueComment(*gComment.Comment, jComment, jIssues[0], *s.gClient, *s.jClient)
// 	break
// }

// if !found {
// 	_, err := s.createIssueComment(jIssues[0], *gComment.Comment, *s.gClient)
// 	if err != nil {
// 		return errors.Trace(err)
// 	}
// }

// return nil
//}

// UpdateComment compares the body of a GitHub comment with the body (minus header)
// of the JIRA comment, and updates the JIRA comment if necessary.
// func (s *Server) updateIssueComment(ghComment github.IssueComment, jComment jira.Comment, jIssue jira.Issue, ghClient github.Client, jClient JIRAClient) error {
// 	// fields[0] is the whole body, 1 is the ID, 2 is the username, 3 is the real name (or "" if none)
// 	// 4 is the date, and 5 is the real body
// 	fields := jCommentRegex.FindStringSubmatch(jComment.Body)

// 	if fields[5] == ghComment.GetBody() {
// 		return nil
// 	}

// 	err := s.jClient.UpdateComment(jIssue, jComment.ID, ghComment, ghClient)
// 	return errors.Trace(err)
// }

// func (s *Server) createIssueComment(issue jira.Issue, comment github.IssueComment, gClient github.Client) (jira.Comment, error) {
// 	com, err := s.jClient.CreateComment(issue, comment, gClient)
// 	if err != nil {
// 		log.Errorf("createIssueComment error %v", err)
// 		return jira.Comment{}, errors.Trace(err)
// 	}

// 	log.Debugf("createIssueComment successful comment %s", com.ID)
// 	return com, nil
// }

// func (s *Server) do(req *http.Request) (*http.Response, error) {
// 	var resp *http.Response
// 	var err error
// 	backoff := 100 * time.Millisecond
// 	maxRetries := 5

// 	for retries := 0; retries < maxRetries; retries++ {
// 		resp, err = s.c.Do(req)
// 		if err == nil {
// 			break
// 		}
// 		time.Sleep(backoff)
// 		backoff *= 2
// 	}
// 	return resp, err
// }

// func (s *Server) ListGithubIssues() ([]github.Issue, error) {
// 	issues, resp, err := issuess.gClient.Issue.ListByRepo(context.Background(), )
// }

// func (s *Server) createIssue(issue github.Issue, ghClient *github.Client, jClient *JIRAClient) error {
// 	log.Debugf("Creating JIRA issue based on GitHub issue #%d", *issue.Number)
// 	//idMap[*issue.Number] = "ture"
// 	compoName := s.cfg.getComponents()
// 	components := make([]*jira.Component, len(compoName))
// 	for i := 0; i < len(compoName); i++ {
// 		components[i] = &jira.Component{}
// 		components[i].Name = compoName[i]
// 	}

// 	fields := jira.IssueFields{
// 		Type: jira.IssueType{
// 			Name: "Task", // TODO: Determine issue type
// 		},
// 		Project: jira.Project{
// 			Key: s.cfg.getProject(),
// 		},
// 		Components:  components,
// 		Summary:     issue.GetTitle(),
// 		Description: issue.GetBody(),
// 		Unknowns:    map[string]interface{}{},
// 	}

// 	fields.Unknowns[s.cfg.getFieldKey(GitHubID)] = issue.GetID()
// 	//	fields.Unknowns[s.cfg.getFieldKey(GitHubNumber)] = issue.GetNumber()
// 	//	fields.Unknowns[s.cfg.getFieldKey(GitHubStatus)] = issue.GetState()
// 	//	fields.Unknowns[s.cfg.getFieldKey(GitHubReporter)] = issue.User.GetLogin()
// 	//	fields.Reporter = &jira.User{Name: issue.User.GetLogin()}
// 	//	fields.Status = &jira.Status{Name: issue.GetState()}
// 	strs := make([]string, len(issue.Labels))
// 	for i, v := range issue.Labels {
// 		strs[i] = *v.Name
// 	}
// 	fields.Labels = strs
// 	//	fields.Unknowns[s.cfg.getFieldKey(GitHubLabels)] = strings.Join(strs, ",")

// 	//fields.Unknowns[s.cfg.getFieldKey(LastISUpdate)] = time.Now().Format(dateFormat)

// 	jIssue := jira.Issue{
// 		Fields: &fields,
// 	}

// 	jIssue, err := s.jClient.CreateIssue(jIssue)
// 	if err != nil {
// 		return errors.Trace(err)
// 	}

// 	is, _, err := jClient.client.Issue.Get(jIssue.Key, nil)
// 	if err != nil {
// 		return errors.Trace(err)
// 	}

// 	log.Debugf("Created JIRA issue %s!", is.Key)
// 	// idMap[issue.GetID()] = is.ID
// 	// // if err := CompareComments(config, issue, jIssue, ghClient, jClient); err != nil {
// 	// // 	return errors.Trace(err)
// 	// // }

// 	return nil
// }

// func (s *Server) updateIssue(ghIssue github.Issue, jIssue jira.Issue, ghClient *github.Client, jClient *JIRAClient) error {
// 	log.Debugf("Updating JIRA %s with GitHub #%d", jIssue.Key, *ghIssue.Number)

// 	var issue jira.Issue

// 	if DidIssueChange(s.cfg, ghIssue, jIssue) {
// 		fields := jira.IssueFields{}
// 		fields.Unknowns = map[string]interface{}{}

// 		fields.Summary = ghIssue.GetTitle()
// 		fields.Description = ghIssue.GetBody()
// 		//	fields.Unknowns[s.cfg.getFieldKey(GitHubStatus)] = ghIssue.GetState()
// 		//	fields.Unknowns[s.cfg.getFieldKey(GitHubReporter)] = ghIssue.User.GetLogin()

// 		labels := make([]string, len(ghIssue.Labels))
// 		for i, l := range ghIssue.Labels {
// 			labels[i] = l.GetName()
// 		}
// 		//	fields.Unknowns[s.cfg.getFieldKey(GitHubLabels)] = strings.Join(labels, ",")

// 		//fields.Unknowns[s.cfg.getFieldKey(LastISUpdate)] = time.Now().Format(dateFormat)

// 		fields.Type = jIssue.Fields.Type
// 		fields.Labels = labels

// 		issue = jira.Issue{
// 			Fields: &fields,
// 			Key:    jIssue.Key,
// 			ID:     jIssue.ID,
// 		}

// 		var err error
// 		issue, err = jClient.updateIssue(issue)
// 		if err != nil {
// 			return err
// 		}

// 		log.Debugf("Successfully updated JIRA issue %s!", jIssue.Key)
// 	} else {
// 		log.Debugf("JIRA issue %s is already up to date!", jIssue.Key)
// 	}

// 	// issue, err := jClient.GetIssue(jIssue.Key)
// 	// if err != nil {
// 	// 	log.Debugf("Failed to retrieve JIRA issue %s!", jIssue.Key)
// 	// 	return err
// 	// }

// 	// if err := CompareComments(config, ghIssue, issue, ghClient, jClient); err != nil {
// 	// 	return err
// 	// }

// 	return nil
// }

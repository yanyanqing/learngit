package main

import (
	//	"context"
	"fmt"
	"io/ioutil"
	"strings"

	"github.com/andygrunwald/go-jira"
	"github.com/juju/errors"
	"github.com/ngaut/log"

	//	"k8s.io/test-infra/prow/config"
	"github.com/google/go-github/github"
	//	"k8s.io/test-infra/prow/plugins"
	//	"k8s.io/test-infra/prow/hook"
)

// commentDateFormat is the format used in the headers of JIRA comments.
const commentDateFormat = "15:04 PM, January 2 2006"

// maxJQLIssueLength is the maximum number of GitHub issues we can
// use before we need to stop using JQL and filter issues ourself.
const maxJQLIssueLength = 100

type JIRAClient struct {
	config *Config
	client *jira.Client
}

func newJIRAClient(config *Config) (*JIRAClient, error) {
	client, err := jira.NewClient(nil, config.JConfig.Endpoint)
	if err != nil {
		log.Errorf("createclient error %v", err)
		return nil, errors.Trace(err)
	}

	client.Authentication.SetBasicAuth(config.JConfig.Username, config.JConfig.Password)

	jClient := &JIRAClient{
		config: config,
		client: client,
	}
	jClient.config.fieldIDs, err = jClient.getFieldIDs()
	return jClient, errors.Trace(err)
}

// GetIssue returns a single JIRA issue within the configured project
// according to the issue key (e.g. "PROJ-13").
func (j *JIRAClient) GetIssue(key string) (jira.Issue, error) {
	log := j.config.getLogger()

	issue, res, err := j.client.Issue.Get(key, nil)

	if err != nil {
		log.Errorf("Error retrieving JIRA issue: %v", err)
		return jira.Issue{}, getErrorBody(j.config, res)
	}

	return *issue, nil
}

func (j *JIRAClient) ListIssues(ids []int) ([]jira.Issue, error) {
	idStrs := make([]string, len(ids))
	for i, v := range ids {
		idStrs[i] = fmt.Sprint(v)
	}

	var jql string
	// If the list of IDs is too long, we get a 414 Request-URI Too Large, so in that case,
	// we'll need to do the filtering ourselves.
	if len(ids) < maxJQLIssueLength {
		jql = fmt.Sprintf("project='%s' AND cf[%s] in (%s)",
			j.config.getProject(), j.config.getFieldID(GitHubID), strings.Join(idStrs, ","))
	} else {
		jql = fmt.Sprintf("project='%s'", j.config.getProject())
	}

	jiraIssues, res, err := j.client.Issue.Search(jql, nil)
	if err != nil {
		log.Errorf("Error retrieving JIRA issues: %v", err)
		return nil, getErrorBody(j.config, res)
	}

	var issues []jira.Issue
	if len(ids) < maxJQLIssueLength {
		// The issues were already filtered by our JQL, so use as is
		issues = jiraIssues
	} else {
		// Filter only issues which have a defined GitHub ID in the list of IDs
		for _, v := range jiraIssues {
			if id, err := v.Fields.Unknowns.Int(j.config.getFieldKey(GitHubID)); err == nil {
				for _, idOpt := range ids {
					if id == int64(idOpt) {
						issues = append(issues, v)
						break
					}
				}
			}
		}
	}

	return issues, nil
}

// UpdateComment updates a comment (identified by the `id` parameter) on a given
// JIRA with a new body from the fields of the given GitHub comment. It returns
// the updated comment.
func (j *JIRAClient) UpdateComment(issue *jira.Issue, id string, comment *github.IssueComment, gClient *GHClient) error {
	user, err := gClient.GetUser(comment.User.GetLogin())
	if err != nil {
		return errors.Trace(err)
	}

	body := fmt.Sprintf("Comment [(ID %d)|%s]", comment.GetID(), comment.GetHTMLURL())
	body = fmt.Sprintf("%s from GitHub user [%s|%s]", body, user.GetLogin(), user.GetHTMLURL())
	if user.GetName() != "" {
		body = fmt.Sprintf("%s (%s)", body, user.GetName())
	}
	body = fmt.Sprintf(
		"%s at %s:\n\n%s",
		body,
		comment.CreatedAt.Format(commentDateFormat),
		comment.GetBody(),
	)

	// As it is, the JIRA API we're using doesn't have any way to update comments natively.
	// So, we have to build the request ourselves.
	request := struct {
		Body string `json:"body"`
	}{
		Body: body,
	}

	req, err := j.client.NewRequest("PUT", fmt.Sprintf("rest/api/2/issue/%s/comment/%s", issue.Key, id), request)
	if err != nil {
		log.Errorf("Error creating comment update request: %s", err)
		return errors.Trace(err)
	}

	res, err := j.client.Do(req, nil)
	if err != nil {
		data, _ := ioutil.ReadAll(res.Body)
		log.Errorf("Error updating comment resp %v error %v", string(data), err)
		return errors.Trace(err)
	}

	return nil
}

// CreateComment adds a comment to the provided JIRA issue using the fields from
// the provided GitHub comment. It then returns the created comment.
func (j *JIRAClient) CreateComment(issue *jira.Issue, comment *github.IssueComment, gClient *GHClient) (jira.Comment, error) {
	user, err := gClient.GetUser(comment.User.GetLogin())
	if err != nil {
		return jira.Comment{}, errors.Trace(err)
	}

	body := fmt.Sprintf("Comment [(ID %d)|%s]", comment.GetID(), comment.GetHTMLURL())
	body = fmt.Sprintf("%s from GitHub user [%s|%s]", body, user.GetLogin(), user.GetHTMLURL())
	if user.GetName() != "" {
		body = fmt.Sprintf("%s (%s)", body, user.GetName())
	}
	body = fmt.Sprintf(
		"%s at %s:\n\n%s",
		body,
		comment.CreatedAt.Format(commentDateFormat),
		comment.GetBody(),
	)

	jComment := jira.Comment{
		Body: body,
	}

	com, res, err := j.client.Issue.AddComment(issue.ID, &jComment)
	if err != nil {
		data, _ := ioutil.ReadAll(res.Body)
		log.Errorf("Error creating JIRA comment on issue %s. comment %v Error: %v resp %v", issue.Key, com, err, string(data))
		return jira.Comment{}, errors.Trace(err)
	}

	return *com, nil
}

// updateIssue updates a given issue (identified by the Key field of the provided
// issue object) with the fields on the provided issue. It returns the updated
// issue as it exists on JIRA.
func (j *JIRAClient) UpdateIssue(issue jira.Issue) (jira.Issue, error) {
	i, res, err := j.client.Issue.Update(&issue)
	if err != nil {
		log.Errorf("Error updating JIRA issue %s: %v", issue.Key, err)
		return jira.Issue{}, getErrorBody(j.config, res)
	}

	return *i, nil
}

// CreateIssue creates a new JIRA issue according to the fields provided in
// the provided issue object. It returns the created issue, with all the
// fields provided (including e.g. ID and Key).
func (j *JIRAClient) CreateIssue(issue jira.Issue) (jira.Issue, error) {
	i, res, err := j.client.Issue.Create(&issue)
	if err != nil {
		log.Errorf("Error creating JIRA issue: %v", err)
		return jira.Issue{}, getErrorBody(j.config, res)
	}

	return *i, nil
}

// getErrorBody reads the HTTP response body of a JIRA API response,
// logs it as an error, and returns an error object with the contents
// of the body. If an error occurs during reading, that error is
// instead printed and returned. This function closes the body for
// further reading.
func getErrorBody(config *Config, res *jira.Response) error {
	defer res.Body.Close()
	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		log.Errorf("Error occured trying to read error body: %v", err)
		return err
	}
	log.Debugf("Error body: %s", body)
	return errors.New(string(body))
}

// DidIssueChange tests each of the relevant fields on the provided JIRA and GitHub issue
// and returns whether or not they differ.
func DidIssueChange(config *Config, ghIssue *github.Issue, jIssue *jira.Issue) bool {

	log.Debugf("Comparing GitHub issue #%d and JIRA issue %s", ghIssue.GetNumber(), jIssue.Key)

	anyDifferent := false

	anyDifferent = anyDifferent || (ghIssue.GetTitle() != jIssue.Fields.Summary)
	anyDifferent = anyDifferent || (ghIssue.GetBody() != jIssue.Fields.Description)

	key := config.getFieldKey(GitHubStatus)
	field, err := jIssue.Fields.Unknowns.String(key)
	if err != nil || *ghIssue.State != field {
		anyDifferent = true
	}

	key = config.getFieldKey(GitHubReporter)
	field, err = jIssue.Fields.Unknowns.String(key)
	if err != nil || *ghIssue.User.Login != field {
		anyDifferent = true
	}

	labels := make([]string, len(ghIssue.Labels))
	for i, l := range ghIssue.Labels {
		labels[i] = *l.Name
	}

	key = config.getFieldKey(GitHubLabels)
	field, err = jIssue.Fields.Unknowns.String(key)
	if err != nil && strings.Join(labels, ",") != field {
		anyDifferent = true
	}
	log.Infof("field %v lables %v", field, labels)

	log.Debugf("Issues have any differences: %b", anyDifferent)

	return anyDifferent
}

// jiraField represents field metadata in JIRA. For an example of its
// structure, make a request to `${jira-uri}/rest/api/2/field`.
type jiraField struct {
	ID          string   `json:"id"`
	Key         string   `json:"key"`
	Name        string   `json:"name"`
	Custom      bool     `json:"custom"`
	Orderable   bool     `json:"orderable"`
	Navigable   bool     `json:"navigable"`
	Searchable  bool     `json:"searchable"`
	ClauseNames []string `json:"clauseNames"`
	Schema      struct {
		Type     string `json:"type"`
		System   string `json:"system,omitempty"`
		Items    string `json:"items,omitempty"`
		Custom   string `json:"custom,omitempty"`
		CustomID int    `json:"customId,omitempty"`
	} `json:"schema,omitempty"`
}

// getFieldIDs requests the metadata of every issue field in the JIRA
// project, and saves the IDs of the custom fields used by issue-sync.
func (j *JIRAClient) getFieldIDs() (fields, error) {
	req, err := j.client.NewRequest("GET", "rest/api/2/field", nil)
	if err != nil {
		return fields{}, errors.Trace(err)
	}
	jFields := new([]jiraField)

	_, err = j.client.Do(req, jFields)
	if err != nil {
		return fields{}, errors.Trace(err)
	}

	fieldIDs := fields{}

	for _, field := range *jFields {
		switch field.Name {
		case "GitHub ID":
			fieldIDs.githubID = fmt.Sprint(field.Schema.CustomID)
		case "GitHub Number":
			fieldIDs.githubNumber = fmt.Sprint(field.Schema.CustomID)
		case "GitHub Labels":
			fieldIDs.githubLabels = fmt.Sprint(field.Schema.CustomID)
		case "GitHub Status":
			fieldIDs.githubStatus = fmt.Sprint(field.Schema.CustomID)
		case "GitHub Reporter":
			fieldIDs.githubReporter = fmt.Sprint(field.Schema.CustomID)
		case "Last Issue-Sync Update":
			fieldIDs.lastUpdate = fmt.Sprint(field.Schema.CustomID)
		}
	}

	if fieldIDs.githubID == "" {
		return fieldIDs, errors.New("could not find ID of 'GitHub ID' custom field; check that it is named correctly")
	} 

	log.Debug("All fields have been checked.")

	return fieldIDs, nil
}

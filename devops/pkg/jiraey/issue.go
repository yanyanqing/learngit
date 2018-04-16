package main

import (
	"strings"

	"github.com/andygrunwald/go-jira"
	"github.com/google/go-github/github"
	"github.com/juju/errors"
)

// dateFormat is the format used for the Last IS Update field
const dateFormat = "2006-01-02T15:04:05-0700"

// CompareIssues gets the list of GitHub issues updated since the `since` date,
// gets the list of JIRA issues which have GitHub ID custom fields in that list,
// then matches each one. If a JIRA issue already exists for a given GitHub issue,
// it calls UpdateIssue; if no JIRA issue already exists, it calls CreateIssue.
func CompareIssues(config *Config, ghClient *GHClient, jiraClient *JIRAClient) error {
	log := config.GetLogger()

	log.Info("Collecting issues")

	rules := config.GetRules()
	for i := 0; i < len(rules); i++ {
		ghIssues, err := ghClient.ListIssues(rules[i].Repo)
		if err != nil {
			return errors.Trace(err)
		}

		if len(ghIssues) == 0 {
			log.Infof("There are no GitHub issues; exiting")
			return nil
		}

		ids := make([]int, len(ghIssues))
		for i, v := range ghIssues {
			ids[i] = int(v.GetID())
		}

		jiraIssues, err := jiraClient.ListIssues(rules[i].Project, ids)
		if err != nil {
			return errors.Trace(err)
		}

		for _, ghIssue := range ghIssues {
			found := false
			for _, jIssue := range jiraIssues {
				id, _ := jIssue.Fields.Unknowns.Int(config.GetFieldKey(GitHubID))
				if int64(*ghIssue.ID) == id {
					found = true
					if err := UpdateIssue(config, ghIssue, &jIssue, ghClient, jiraClient); err != nil {
						log.Errorf("Error updating issue %s. Error: %v", jIssue.Key, err)
					}
					break
				}
			}
			if !found {
				if err := CreateIssue(config, ghIssue, ghClient, jiraClient); err != nil {
					log.Errorf("Error creating issue for #%d. Error: %v", *ghIssue.Number, err)
				}
			}
		}
	}

	return nil
}

func DeleteIssue(config *Config, issue *jira.Issue, jClient *JIRAClient) error {
	res, err := jClient.client.Issue.Delete(issue.ID)
	if err != nil {
		return GetErrorBody(config, res)
	}

	return nil
}

// CreateIssue generates a JIRA issue from the various fields on the given GitHub issue, then
// sends it to the JIRA API.
func CreateIssue(config *Config, gIssue *github.Issue, ghClient *GHClient, jClient *JIRAClient) error {
	log := config.GetLogger()

	repoURL := strings.Split(*gIssue.RepositoryURL, "/")
	repo, user := repoURL[len(repoURL)-1], repoURL[len(repoURL)-2]
	//repo := *gIssue.Repository.FullName
	log.Infof("Creating JIRA issue based on GitHub issue #%d, SrcRepo %v", *gIssue.Number, repo)

	compoName := config.GetComponents(user + "/" + repo)
	strs := make([]string, len(compoName))
	components := make([]*jira.Component, len(compoName))
	for i := 0; i < len(compoName); i++ {
		components[i] = &jira.Component{}
		components[i].Name = compoName[i]
	}

	fields := jira.IssueFields{
		Type: jira.IssueType{
			Name: "Task", // TODO: Determine issue type
		},
		Project: jira.Project{
			Key: config.GetProject(user + "/" + repo),
		},
		Components:  components,
		Summary:     gIssue.GetTitle(),
		Description: gIssue.GetBody(),
		Unknowns:    map[string]interface{}{},
	}

	fields.Unknowns[config.GetFieldKey(GitHubID)] = gIssue.GetID()
	//fields.Unknowns[config.GetFieldKey(GitHubNumber)] = gIssue.GetID()
	//if len(gIssue.Labels) == 0{
	//	return nil
	//}
	//log.Infof("*gIssue %v ass %v", *gIssue, fields.Assignee)
	/*if gIssue.Assignee != nil{
		fields.Assignee = &jira.User{
			Name : *gIssue.Assignee.Login,
		}
	}

	fields.Assignee = &jira.User{
		Name : "Zhang Bokang",
	} */
	/* strs := make([]string, len(gIssue.Labels))
	for i, v := range gIssue.Labels {
		strs[i] = *v.Name
	}*/
	fields.Labels = strs
	jIssue := jira.Issue{
		Fields: &fields,
	}

	jIssue, err := jClient.CreateIssue(jIssue)
	if err != nil {
		return errors.Trace(err)
	}

	jIssue, err = jClient.GetIssue(jIssue.Key)
	if err != nil {
		return errors.Trace(err)
	}

	log.Infof("Created JIRA issue %s!", jIssue.Key)

	if err := CompareComments(config, gIssue, &jIssue, ghClient, jClient); err != nil {
		return errors.Trace(err)
	}

	return nil
}

// UpdateIssue compares each field of a GitHub issue to a JIRA issue; if any of them
// differ, the differing fields of the JIRA issue are updated to match the GitHub
// issue.
func UpdateIssue(config *Config, ghIssue *github.Issue, jIssue *jira.Issue, ghClient *GHClient, jClient *JIRAClient) error {
	log := config.GetLogger()

	log.Infof("Updating JIRA %s with GitHub #%d", jIssue.Key, *ghIssue.Number)

	var issue jira.Issue

	if DidIssueChange(config, ghIssue, jIssue) {
		fields := jira.IssueFields{}
		fields.Unknowns = map[string]interface{}{}

		fields.Summary = ghIssue.GetTitle()
		fields.Description = ghIssue.GetBody()

		// labels := make([]string, len(ghIssue.Labels))
		// for i, l := range ghIssue.Labels {
		// 	labels[i] = l.GetName()
		// }

		// fields.Type = jIssue.Fields.Type
		// fields.Labels = labels

		issue = jira.Issue{
			Fields: &fields,
			Key:    jIssue.Key,
			ID:     jIssue.ID,
		}

		var err error
		issue, err = jClient.UpdateIssue(issue)
		if err != nil {
			return errors.Trace(err)
		}

		log.Infof("Successfully updated JIRA issue %s!", jIssue.Key)
	} else {
		log.Infof("JIRA issue %s is already up to date!", jIssue.Key)
	}

	issue, err := jClient.GetIssue(jIssue.Key)
	if err != nil {
		log.Infof("Failed to retrieve JIRA issue %s!", jIssue.Key)
		return errors.Trace(err)
	}

	if err := CompareComments(config, ghIssue, &issue, ghClient, jClient); err != nil {
		return errors.Trace(err)
	}

	return nil
}

package main

import (
	"context"
	"strings"
	"time"

	"github.com/google/go-github/github"
	"github.com/juju/errors"
)

type GHClient struct {
	cfg    *Config
	client *github.Client
}

func NewGHClient(cfg *Config) *GHClient {
	auth := &github.BasicAuthTransport{
		Username: cfg.GConfig.Username,
		Password: cfg.GConfig.Password,
	}
	client := github.NewClient(auth.Client())

	return &GHClient{
		cfg:    cfg,
		client: client,
	}
}

// ListIssues returns the list of GitHub issues since the last run of the tool.
func (g *GHClient) ListIssues(repo string) ([]*github.Issue, error) {
	log := g.cfg.GetLogger()

	ctx := context.Background()

	user, repo := g.GetRepo(repo)
	// Set it so that it will run the loop once, and it'll be updated in the loop.
	pages := 1
	var issues []*github.Issue

	log.Infof("user %v, repo %v, g.client %+v", user, repo, g.client)
	for page := 0; page < pages; page++ {
		var is []*github.Issue
		is, res, err := g.client.Issues.ListByRepo(ctx, user, repo, &github.IssueListByRepoOptions{
			Direction: "asc",
			Since:     time.Now().Add(-3 * syncInterval),
			ListOptions: github.ListOptions{
				Page:    page,
				PerPage: 100,
			},
		})
		if err != nil {
			return nil, errors.Trace(err)
		}

		pages = res.LastPage
		issues = append(issues, is...)
	}

	log.Info("Collected all GitHub issues %v", issues)

	return issues, nil
}

// ListComments returns the list of all comments on a GitHub issue in
// ascending order of creation.
func (g *GHClient) ListComments(issue *github.Issue) ([]*github.IssueComment, error) {
	log := g.cfg.GetLogger()

	ctx := context.Background()
	user, repo := g.GetRepo(*issue.Repository.FullName)
	comments, _, err := g.client.Issues.ListComments(ctx, user, repo, issue.GetNumber(), &github.IssueListCommentsOptions{})
	if err != nil {
		log.Errorf("Error retrieving GitHub comments for issue #%d. Error: %v.", issue.GetNumber(), err)
		return nil, errors.Trace(err)
	}

	return comments, nil
}

// GetUser returns a GitHub user from its login.
func (g *GHClient) GetUser(login string) (github.User, error) {
	log := g.cfg.GetLogger()
	u, _, err := g.client.Users.Get(context.Background(), login)
	if err != nil {
		log.Errorf("Error retrieving GitHub user %s. Error: %v", login, err)
	}

	return *u, nil
}

// GetRepo returns a github repo
func (g *GHClient) GetRepo(repo string) (string, string) {
	parts := strings.Split(repo, "/")
	// We check that repo-name is two parts separated by a slash in NewConfig, so this is safe
	return parts[0], parts[1]
}

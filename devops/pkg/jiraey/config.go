package main

import (
	"flag"
	"fmt"
	"os"
	//	"strings"
	"time"

	"github.com/BurntSushi/toml"
	"github.com/juju/errors"
	"github.com/sirupsen/logrus"
)

type SyncConfig struct {
	Repo       string   `toml:"github-repo" json:"github-repo"`
	Project    string   `toml:"jira-project" json:"jira-project"`
	Components []string `toml:"jira-components" json:"jira-components"`
}

type GHConfig struct {
	Username string `toml:"github-username" json:"github-username"`
	Password string `toml:"github-password" json:"github-password"`
}

type JIRAConfig struct {
	Username string `toml:"jira-username" json:"jira-username"`
	Password string `toml:"jira-password" json:"jira-password"`
	Endpoint string `toml:"jira-endpoint" json:"jira-endpoint"`
}

// fieldKey is an enum-like type to represent the customfield ID keys
type fieldKey int

const (
	GitHubID       fieldKey = iota
	GitHubNumber   fieldKey = iota
	GitHubLabels   fieldKey = iota
	GitHubStatus   fieldKey = iota
	GitHubReporter fieldKey = iota
	LastISUpdate   fieldKey = iota
)

// fields represents the custom field IDs of the JIRA custom fields we care about
type fields struct {
	githubID       string
	githubNumber   string
	githubLabels   string
	githubReporter string
	githubStatus   string
	lastUpdate     string
}

type Config struct {
	*flag.FlagSet

	Port string `toml:"port" json:"port"`

	Rules   []*SyncConfig `toml:"sync-config" json:"sync-config"`
	JConfig *JIRAConfig   `toml:"jira-config" json:"jira-config"`
	GConfig *GHConfig     `toml:"github-config" json:"github-config"`
	repoMap map[string]*SyncConfig
	// log is a logger set up with the configured log level, app name, etcfg.
	log *logrus.Entry

	// basicAuth represents whether we're using HTTP Basic authentication or OAuth.
	basicAuth bool

	// fieldIDs is the list of custom fields we pulled from the `fields` JIRA endpoint.
	fieldIDs fields

	// since is the parsed value of the `since` configuration parameter, which is the earliest that
	// a GitHub issue can have been updated to be retrieved.
	since time.Time

	configFile string
}

func NewConfig() *Config {
	cfg := &Config{}
	cfg.GConfig = &GHConfig{}
	cfg.JConfig = &JIRAConfig{}
	cfg.repoMap = make(map[string]*SyncConfig)
	cfg.FlagSet = flag.NewFlagSet("jiraey", flag.ContinueOnError)
	fs := cfg.FlagSet

	logger := logrus.New()
	cfg.log = logrus.NewEntry(logger).WithFields(logrus.Fields{
		"app": "jiraey",
	})
	fs.StringVar(&cfg.configFile, "config", "", "path to config file")
	fs.StringVar(&cfg.GConfig.Username, "github-username", "sre-robot", "")
	fs.StringVar(&cfg.GConfig.Password, "github-password", "N8^^y4#8Q0", "")
	fs.StringVar(&cfg.JConfig.Username, "jira-username", "sre-bot", "")
	fs.StringVar(&cfg.JConfig.Password, "jira-password", "N8^^y4#8Q0", "")
	fs.StringVar(&cfg.JConfig.Endpoint, "jira-endpoint", "", "jira endpoint for dispatch issue")

	return cfg
}

// Parse parses all config from command-line flags
func (cfg *Config) Parse(args []string) error {
	perr := cfg.FlagSet.Parse(args)
	switch perr {
	case nil:
	case flag.ErrHelp:
		os.Exit(0)
	default:
		os.Exit(2)
	}

	// Load config file if specified.
	if cfg.configFile != "" {
		err := cfg.configFromFile(cfg.configFile)
		if err != nil {
			return errors.Trace(err)
		}
	}

	// Parse again to replace with command line options.
	err := cfg.FlagSet.Parse(args)
	if err != nil {
		return errors.Trace(err)
	}

	err = cfg.validate()
	if err != nil {
		return errors.Trace(err)
	}

	cfg.adjust()

	return nil
}

func (cfg *Config) validate() error {
	if len(cfg.FlagSet.Args()) != 0 {
		return errors.Errorf("'%s' is an invalid flag", cfg.FlagSet.Arg(0))
	}

	if cfg.JConfig.Endpoint == "" {
		return errors.New("jira endpoint should be given")
	}

	return nil
}

func (cfg *Config) adjust() {
	for i := 0; i < len(cfg.Rules); i++ {
		key := cfg.Rules[i].Repo
		cfg.repoMap[key] = cfg.Rules[i]
	}
	cfg.log.Infof("repoMap %v", cfg.repoMap)
}

func (cfg *Config) GetRepoMap() map[string]*SyncConfig {
	return cfg.repoMap
}

func (cfg *Config) GetRules() []*SyncConfig {
	return cfg.Rules
}

// GetFieldID returns the customfield ID of a JIRA custom field.
func (cfg *Config) GetFieldID(key fieldKey) string {
	switch key {
	case GitHubID:
		return cfg.fieldIDs.githubID
	case GitHubNumber:
		return cfg.fieldIDs.githubNumber
	case GitHubLabels:
		return cfg.fieldIDs.githubLabels
	case GitHubReporter:
		return cfg.fieldIDs.githubReporter
	case GitHubStatus:
		return cfg.fieldIDs.githubStatus
	case LastISUpdate:
		return cfg.fieldIDs.lastUpdate
	default:
		return ""
	}
}

// getFieldKey returns customfield_XXXXX, where XXXXX is the custom field ID (see GetFieldID).
func (cfg *Config) GetFieldKey(key fieldKey) string {
	return fmt.Sprintf("customfield_%s", cfg.GetFieldID(key))
}

// getProject returns the JIRA project the user has configured.
func (cfg *Config) GetProject(repo string) string {
	return cfg.repoMap[repo].Project
}

func (cfg *Config) GetComponents(repo string) []string {
	return cfg.repoMap[repo].Components
}
func (cfg *Config) configFromFile(configFile string) error {
	_, err := toml.DecodeFile(configFile, cfg)
	return errors.Trace(err)
}

// getLogger returns the configured application logger.
func (cfg *Config) GetLogger() *logrus.Entry {
	return cfg.log
}

// // getRepo returns the user/org name and the repo name of the configured GitHub repository.
// func (cfg *Config) getRepo() (string, string) {
// 	parts := strings.Split(cfg.GConfig.Repo, "/")
// 	// We check that repo-name is two parts separated by a slash in NewConfig, so this is safe
// 	return parts[0], parts[1]
// }

// // getSinceParam returns the `since` configuration parameter, parsed as a time.Time.
// func (cfg *Config) getSinceParam() time.Time {
// 	return cfg.since
// }
